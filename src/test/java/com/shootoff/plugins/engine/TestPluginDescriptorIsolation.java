package com.shootoff.plugins.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.shootoff.plugins.TrainingExerciseBase;

class TestPluginDescriptorIsolation {
	private static final String EXERCISE_CLASS = "com.example.testplugin.TestExercise";

	private static final String EXERCISE_SOURCE = String.join("\n",
			"package com.example.testplugin;",
			"import java.util.List;",
			"import java.util.Optional;",
			"import com.shootoff.camera.Shot;",
			"import com.shootoff.plugins.ExerciseMetadata;",
			"import com.shootoff.plugins.TrainingExercise;",
			"import com.shootoff.plugins.TrainingExerciseBase;",
			"import com.shootoff.targets.Hit;",
			"import com.shootoff.targets.Target;",
			"public class TestExercise extends TrainingExerciseBase implements TrainingExercise {",
			"  @Override public void init() {}",
			"  @Override public void targetUpdate(Target target, TargetChange change) {}",
			"  @Override public ExerciseMetadata getInfo() {",
			"    return new ExerciseMetadata(\"Test Exercise\", \"1.0\", \"ShootOFF tests\", \"Loaded from a jar\");",
			"  }",
			"  @Override public void shotListener(Shot shot, Optional<Hit> hit) {}",
			"  @Override public void reset(List<Target> targets) {}",
			"  @Override public void destroy() {}",
			"}");

	private static final String DESCRIPTOR = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
			+ "<shootoffExercise exerciseClass=\"" + EXERCISE_CLASS + "\" />";

	@TempDir Path tempDir;

	private Path buildPluginJar(boolean includeDescriptor) throws IOException {
		final Path source = tempDir.resolve("src/com/example/testplugin/TestExercise.java");
		Files.createDirectories(source.getParent());
		Files.write(source, EXERCISE_SOURCE.getBytes(StandardCharsets.UTF_8));

		final Path classes = tempDir.resolve("classes");
		Files.createDirectories(classes);
		// Compile against ShootOFF's classes the way a plugin author would
		final String classpath = System.getProperty("java.class.path") + File.pathSeparator
				+ new File(TrainingExerciseBase.class.getProtectionDomain().getCodeSource().getLocation().getPath());
		final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		assertEquals(0, compiler.run(null, null, null, "-classpath", classpath, "-d", classes.toString(),
				source.toString()), "test plugin failed to compile");

		final Path jar = tempDir.resolve(includeDescriptor ? "plugin.jar" : "no-descriptor.jar");
		try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
			if (includeDescriptor) addEntry(out, "shootoff.xml", DESCRIPTOR.getBytes(StandardCharsets.UTF_8));
			addEntry(out, "com/example/testplugin/TestExercise.class",
					Files.readAllBytes(classes.resolve("com/example/testplugin/TestExercise.class")));
		}
		return jar;
	}

	private static void addEntry(JarOutputStream out, String name, byte[] contents) throws IOException {
		out.putNextEntry(new JarEntry(name));
		out.write(contents);
		out.closeEntry();
	}

	@Test
	void testLoadsExerciseFromJarUsingItsOwnDescriptor() throws Exception {
		final Plugin plugin = new Plugin(buildPluginJar(true));

		assertEquals(EXERCISE_CLASS, plugin.getExercise().getClass().getName());
		assertEquals("Test Exercise", plugin.getExercise().getInfo().getName());
		assertEquals(PluginType.STANDARD, plugin.getType());
	}

	@Test
	void testJarWithoutDescriptorIsRejected() throws Exception {
		final Path jar = buildPluginJar(false);

		assertThrows(IllegalArgumentException.class, () -> new Plugin(jar));
	}
}
