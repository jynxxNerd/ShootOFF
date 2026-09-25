package com.shootoff.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TestHardwareData {
	@Test
	public void testReportsInstalledRam() {
		assertTrue(HardwareData.getMegabytesOfRam() > 0);
	}

	@Test
	public void testReportsCpuName() {
		assertFalse(HardwareData.getCpuName().trim().isEmpty());
	}
}
