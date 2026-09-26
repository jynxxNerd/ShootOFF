package com.shootoff.plugins.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.shootoff.camera.Shot;
import com.shootoff.exercise.Exercise;
import com.shootoff.plugins.ExerciseMetadata;
import com.shootoff.plugins.TrainingExerciseBase;

/**
 * A v2 plugin jar whose exercise references a class missing from its own jar throws a
 * {@link LinkageError} (typically {@link NoClassDefFoundError}) the first time its metadata is read.
 * One broken jar must not stop the rest of the Training menu from loading, and must not kill the
 * plugin watcher thread.
 */
class TestPluginEngineFaultTolerance {
	private static final String MISSING_CLASS = "com.example.v2.Missing";
	private static final String MISSING_SOURCE = String.join("\n",
			"package com.example.v2;",
			"public class Missing {",
			"  public Missing() {}",
			"}");

	private static final String BROKEN_CLASS = "com.example.v2.Broken";
	private static final String BROKEN_SOURCE = String.join("\n",
			"package com.example.v2;",
			"import java.util.Optional;",
			"import com.shootoff.camera.Shot;",
			"import com.shootoff.exercise.Exercise;",
			"import com.shootoff.exercise.ExerciseHost;",
			"import com.shootoff.plugins.ExerciseMetadata;",
			"import com.shootoff.targets.model.Hit;",
			"public class Broken implements Exercise {",
			"  @Override public ExerciseMetadata metadata() {",
			"    new Missing();",
			"    return new ExerciseMetadata(\"Broken\", \"1.0\", \"ShootOFF tests\", \"Missing a class\", false);",
			"  }",
			"  @Override public void start(ExerciseHost host) {}",
			"  @Override public void onShot(Shot shot, Optional<Hit> hit) {}",
			"  @Override public void onReset() {}",
			"  @Override public void stop() {}",
			"}");

	private static final String GOOD_CLASS = "com.example.v2.Good";
	private static final String GOOD_SOURCE = String.join("\n",
			"package com.example.v2;",
			"import java.util.Optional;",
			"import com.shootoff.camera.Shot;",
			"import com.shootoff.exercise.Exercise;",
			"import com.shootoff.exercise.ExerciseHost;",
			"import com.shootoff.plugins.ExerciseMetadata;",
			"import com.shootoff.targets.model.Hit;",
			"public class Good implements Exercise {",
			"  @Override public ExerciseMetadata metadata() {",
			"    return new ExerciseMetadata(\"Good\", \"1.0\", \"ShootOFF tests\", \"Loads fine\", false);",
			"  }",
			"  @Override public void start(ExerciseHost host) {}",
			"  @Override public void onShot(Shot shot, Optional<Hit> hit) {}",
			"  @Override public void onReset() {}",
			"  @Override public void stop() {}",
			"}");

	private record TestEntry(ExerciseMetadata metadata, boolean isProjectorOnly, Class<?> exerciseClass)
			implements ExerciseEntry {}

	private static final class RecordingListener implements PluginListener {
		final List<String> registered = new CopyOnWriteArrayList<>();
		final List<String> unregistered = new CopyOnWriteArrayList<>();

		@Override
		public void registerExercise(ExerciseEntry exercise) {
			registered.add(exercise.metadata().getName());
		}

		@Override
		public void registerProjectorExercise(ExerciseEntry exercise) {
			registered.add(exercise.metadata().getName());
		}

		@Override
		public void unregisterExercise(ExerciseEntry exercise) {
			unregistered.add(exercise.metadata().getName());
		}
	}

	@TempDir Path plugins;
	// Where helper jars and other build artifacts land: never watched or enumerated as a plugin
	@TempDir Path work;
	private String previousPlugins;

	// ShootOFF's classes, as a plugin author compiles against them
	private static String classpath() {
		return String.join(File.pathSeparator, System.getProperty("java.class.path"), location(TrainingExerciseBase.class),
				location(Exercise.class), location(Shot.class));
	}

	private static String location(Class<?> type) {
		return new File(type.getProtectionDomain().getCodeSource().getLocation().getPath()).getPath();
	}

	// A jar whose exercise's metadata() needs a class that isn't packaged with it: compile it
	// against a helper class that never gets jarred, exactly as a plugin author's mismatched build
	// might ship.
	private Path brokenJar(String jarName) throws IOException {
		final Path helperJar = PluginJars.build(work, jarName + "-helper.jar", Optional.empty(),
				Map.of(MISSING_CLASS, MISSING_SOURCE), classpath());

		// Built next door, then moved into place: the watcher must see one CREATE event for a
		// complete file, not a jar that's still being written.
		final Path built = PluginJars.build(work, jarName, Optional.of(PluginJars.descriptor(2, BROKEN_CLASS)),
				Map.of(BROKEN_CLASS, BROKEN_SOURCE), classpath() + File.pathSeparator + helperJar);
		return Files.move(built, plugins.resolve(jarName), StandardCopyOption.ATOMIC_MOVE);
	}

	private Path goodJar(String jarName) throws IOException {
		final Path built = PluginJars.build(work, jarName, Optional.of(PluginJars.descriptor(2, GOOD_CLASS)),
				Map.of(GOOD_CLASS, GOOD_SOURCE), classpath());
		return Files.move(built, plugins.resolve(jarName), StandardCopyOption.ATOMIC_MOVE);
	}

	private static void waitFor(BooleanSupplier condition, String failure) throws InterruptedException {
		final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (!condition.getAsBoolean()) {
			if (System.nanoTime() > deadline) throw new AssertionError(failure);
			Thread.sleep(20);
		}
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
	void aBrokenJarIsSkippedSoTheRestOfTheMenuStillLoads() throws IOException {
		brokenJar("broken.jar");

		final RecordingListener listener = new RecordingListener();
		final ExerciseEntry standard = new TestEntry(
				new ExerciseMetadata("Standard A", "1.0", "phrack", "A built-in", false), false, Object.class);
		final ExerciseEntry projector = new TestEntry(
				new ExerciseMetadata("Projector B", "1.0", "phrack", "A built-in", true), true, Object.class);

		final PluginEngine engine = new PluginEngine(listener, ExerciseLoaders.all(), List.of(standard, projector));

		assertEquals(List.of("Standard A", "Projector B"), listener.registered);
		assertTrue(engine.getPlugins().isEmpty());
		assertFalse(listener.registered.contains("Broken"));
	}

	@Test
	void theWatcherSurvivesABrokenJarAndStillRegistersAGoodOneLater() throws Exception {
		final RecordingListener listener = new RecordingListener();
		final PluginEngine engine = new PluginEngine(listener, ExerciseLoaders.all(), List.of());

		engine.startWatching();
		try {
			brokenJar("broken.jar");
			// The watcher must not die processing the broken jar's creation event.
			Thread.sleep(200);
			assertFalse(listener.registered.contains("Broken"));

			goodJar("good.jar");
			waitFor(() -> listener.registered.contains("Good"),
					"the watcher never registered the good jar; saw " + listener.registered);
		} finally {
			engine.stopWatching();
		}
	}
}
