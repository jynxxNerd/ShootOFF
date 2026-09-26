package com.shootoff.plugins.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.shootoff.plugins.ExerciseMetadata;

class TestPluginEngineRegistration {
	@TempDir Path plugins;
	private String previousPlugins;
	private final List<String> events = new ArrayList<>();

	private record TestEntry(ExerciseMetadata metadata, boolean isProjectorOnly, Class<?> exerciseClass)
			implements ExerciseEntry {}

	// Loads classes that implement Supplier<String>, returning "name|version|projectorOnly"
	private static final ExerciseLoader TEST_LOADER = new ExerciseLoader() {
		@Override
		public int apiVersion() {
			return 2;
		}

		@Override
		public ExerciseEntry load(Class<?> exerciseClass) {
			try {
				@SuppressWarnings("unchecked")
				final String[] info = ((Supplier<String>) exerciseClass.getDeclaredConstructor().newInstance()).get()
						.split("\\|");
				final boolean projectorOnly = Boolean.parseBoolean(info[2]);
				return new TestEntry(new ExerciseMetadata(info[0], info[1], "tester", "A test exercise", projectorOnly),
						projectorOnly, exerciseClass);
			} catch (final ReflectiveOperationException e) {
				throw new IllegalArgumentException(e);
			}
		}
	};

	private final PluginListener listener = new PluginListener() {
		@Override
		public void registerExercise(ExerciseEntry exercise) {
			events.add("+" + label(exercise));
		}

		@Override
		public void registerProjectorExercise(ExerciseEntry exercise) {
			events.add("+projector " + label(exercise));
		}

		@Override
		public void unregisterExercise(ExerciseEntry exercise) {
			events.add("-" + label(exercise));
		}
	};

	private static String label(ExerciseEntry exercise) {
		return exercise.metadata().getName() + " " + exercise.metadata().getVersion();
	}

	private static ExerciseEntry builtIn(String name, boolean projectorOnly) {
		return new TestEntry(new ExerciseMetadata(name, "1.0", "phrack", "A built-in", projectorOnly), projectorOnly,
				Object.class);
	}

	private void jar(String jarName, String className, String info, int apiVersion) throws IOException {
		final String simpleName = className.substring(className.lastIndexOf('.') + 1);
		final String source = "package test; public class " + simpleName
				+ " implements java.util.function.Supplier<String> { public String get() { return \"" + info + "\"; } }";
		PluginJars.build(plugins, jarName, Optional.of(PluginJars.descriptor(apiVersion, className)),
				Map.of(className, source), System.getProperty("java.class.path"));
	}

	// The names left in the menu after all the events
	private List<String> listed() {
		final List<String> listed = new ArrayList<>();
		for (final String event : events) {
			if (event.startsWith("-")) listed.remove(event.substring(1));
			else if (event.startsWith("+projector ")) listed.add(event.substring("+projector ".length()));
			else listed.add(event.substring(1));
		}
		return listed;
	}

	@BeforeEach
	void setUp() {
		previousPlugins = System.getProperty("shootoff.plugins");
		System.setProperty("shootoff.plugins", plugins.toString());
	}

	@AfterEach
	void tearDown() {
		if (previousPlugins == null) System.clearProperty("shootoff.plugins");
		else System.setProperty("shootoff.plugins", previousPlugins);
	}

	@Test
	void builtInsFramePluginsInMenuOrder() throws IOException {
		jar("drill.jar", "test.Drill", "Drill|1.0|false", 2);

		new PluginEngine(listener, List.of(TEST_LOADER),
				List.of(builtIn("Standard A", false), builtIn("Projector B", true), builtIn("Standard C", false)));

		assertEquals(List.of("+Standard A 1.0", "+Standard C 1.0", "+Drill 1.0", "+projector Projector B 1.0"), events);
	}

	@Test
	void newerVersionOfTheSameExerciseWins() throws IOException {
		jar("drill-v1.jar", "test.DrillOne", "Drill|1.1|true", 2);
		jar("drill-v2.jar", "test.DrillTwo", "Drill|2.0|true", 2);

		final PluginEngine engine = new PluginEngine(listener, List.of(TEST_LOADER), List.of());

		assertEquals(1, engine.getPlugins().size());
		assertEquals("2.0", engine.getPlugins().iterator().next().getEntry().metadata().getVersion());
		// Whichever jar was found first, only 2.0 is left in the menu
		assertEquals(List.of("Drill 2.0"), listed());
	}

	@Test
	void jarWithUnsupportedApiVersionIsSkipped() throws IOException {
		jar("future.jar", "test.Future", "Future|1.0|false", 3);
		jar("drill.jar", "test.Drill", "Drill|1.0|false", 2);

		final PluginEngine engine = new PluginEngine(listener, List.of(TEST_LOADER), List.of());

		assertEquals(1, engine.getPlugins().size());
		assertEquals(List.of("+Drill 1.0"), events);
	}
}
