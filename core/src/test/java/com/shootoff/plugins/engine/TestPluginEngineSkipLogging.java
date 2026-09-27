package com.shootoff.plugins.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class TestPluginEngineSkipLogging {
	@TempDir Path plugins;
	private String previousPlugins;
	private final Logger engineLogger = (Logger) LoggerFactory.getLogger(PluginEngine.class);
	private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
	private Level previousLevel;

	// Loads nothing: every jar here is either the wrong API version or broken
	private static final ExerciseLoader V2_ONLY = new ExerciseLoader() {
		@Override
		public int apiVersion() {
			return 2;
		}

		@Override
		public ExerciseEntry load(Class<?> exerciseClass) {
			throw new IllegalArgumentException(exerciseClass + " isn't an exercise");
		}
	};

	private static final PluginListener IGNORE = new PluginListener() {
		@Override
		public void registerExercise(ExerciseEntry exercise) {}

		@Override
		public void registerProjectorExercise(ExerciseEntry exercise) {}

		@Override
		public void unregisterExercise(ExerciseEntry exercise) {}
	};

	@BeforeEach
	void setUp() {
		previousPlugins = System.getProperty("shootoff.plugins");
		System.setProperty("shootoff.plugins", plugins.toString());
		previousLevel = engineLogger.getLevel();
		engineLogger.setLevel(Level.INFO);
		logged.start();
		engineLogger.addAppender(logged);
	}

	@AfterEach
	void tearDown() {
		engineLogger.detachAppender(logged);
		engineLogger.setLevel(previousLevel);
		if (previousPlugins == null) System.clearProperty("shootoff.plugins");
		else System.setProperty("shootoff.plugins", previousPlugins);
	}

	private void jar(String jarName, int apiVersion, String className, Map<String, String> sources) throws IOException {
		PluginJars.build(plugins, jarName, Optional.of(PluginJars.descriptor(apiVersion, className)), sources,
				System.getProperty("java.class.path"));
	}

	@Test
	void aJarForAnotherApiVersionIsSkippedWithOneInfoLine() throws IOException {
		jar("Old.jar", 1, "test.Old", Map.of("test.Old", "package test; public class Old {}"));

		new PluginEngine(IGNORE, List.of(V2_ONLY), List.of());

		assertEquals(1, logged.list.size());
		final ILoggingEvent event = logged.list.get(0);
		assertEquals(Level.INFO, event.getLevel());
		assertEquals("Skipping Old.jar: it needs plugin API version 1, which this app doesn't run",
				event.getFormattedMessage());
		assertNull(event.getThrowableProxy());
	}

	@Test
	void aBrokenJarStillLogsAnErrorWithItsCause() throws IOException {
		// The descriptor names a class the jar doesn't have
		jar("Broken.jar", 2, "test.Missing", Map.of("test.Present", "package test; public class Present {}"));

		new PluginEngine(IGNORE, List.of(V2_ONLY), List.of());

		assertEquals(1, logged.list.size());
		final ILoggingEvent event = logged.list.get(0);
		assertEquals(Level.ERROR, event.getLevel());
		assertEquals("Error creating new plugin", event.getFormattedMessage());
		assertNotNull(event.getThrowableProxy());
	}
}
