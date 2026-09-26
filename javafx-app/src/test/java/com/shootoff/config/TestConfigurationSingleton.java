package com.shootoff.config;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TestConfigurationSingleton {
	@AfterEach
	void leaveAConfigurationCurrent() throws ConfigurationException {
		// Later tests in this JVM expect Configuration.getConfig() to be non-null
		new Configuration(new String[0]);
	}

	@Test
	void newConfigurationIsTheCurrentSettings() throws ConfigurationException {
		final Configuration config = new Configuration(new String[0]);

		assertSame(config, Settings.getSettings());
		assertSame(config, Configuration.getConfig());
	}

	@Test
	void plainSettingsIsNotAConfiguration() throws ConfigurationException {
		final Settings settings = new Settings(new String[0]);

		assertSame(settings, Settings.getSettings());
		assertNull(Configuration.getConfig());
	}
}
