package com.shootoff.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;

import org.junit.jupiter.api.Test;

class TestScratchConfig {
	@Test
	void settingsOnAScratchFileWriteThereAndNotToTheWorkingTree() throws Exception {
		final String before = ScratchConfig.workingTreeFingerprint();
		final File scratch = ScratchConfig.emptyFile();
		final Settings settings = new Settings(scratch.getPath(), new String[0]);

		// Five POI hits turn the adjustment on and write the configuration
		for (int hit = 0; hit < 5; hit++) {
			settings.updatePOIAdjustment(-1, -1);
		}

		assertTrue(settings.isAdjustingPOI());
		assertTrue(Files.readString(scratch.toPath()).contains("shootoff.poiadjust.x"));
		assertEquals(before, ScratchConfig.workingTreeFingerprint());
	}
}
