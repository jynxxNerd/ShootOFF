package com.shootoff.plugins.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.File;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.shootoff.camera.Shot;
import com.shootoff.exercise.Exercise;
import com.shootoff.plugins.ProjectorTrainingExerciseBase;
import com.shootoff.plugins.TrainingExerciseBase;

class TestPluginLoading {
	private static final String V2_CLASS = "com.example.v2.V2Drill";
	private static final String V2_SOURCE = String.join("\n",
			"package com.example.v2;",
			"import java.util.Optional;",
			"import com.shootoff.camera.Shot;",
			"import com.shootoff.exercise.Exercise;",
			"import com.shootoff.exercise.ExerciseHost;",
			"import com.shootoff.plugins.ExerciseMetadata;",
			"import com.shootoff.targets.model.Hit;",
			"public class V2Drill implements Exercise {",
			"  @Override public ExerciseMetadata metadata() {",
			"    return new ExerciseMetadata(\"V2 Drill\", \"1.0\", \"ShootOFF tests\", \"Loaded from a v2 jar\", true);",
			"  }",
			"  @Override public void start(ExerciseHost host) {}",
			"  @Override public void onShot(Shot shot, Optional<Hit> hit) {}",
			"  @Override public void onReset() {}",
			"  @Override public void stop() {}",
			"}");

	private static final String V1_CLASS = "com.example.v1.V1Drill";
	private static final String V1_SOURCE = String.join("\n",
			"package com.example.v1;",
			"import java.util.List;",
			"import java.util.Optional;",
			"import com.shootoff.camera.Shot;",
			"import com.shootoff.plugins.ExerciseMetadata;",
			"import com.shootoff.plugins.ProjectorTrainingExerciseBase;",
			"import com.shootoff.plugins.TrainingExercise;",
			"import com.shootoff.targets.Hit;",
			"import com.shootoff.targets.Target;",
			"public class V1Drill extends ProjectorTrainingExerciseBase implements TrainingExercise {",
			"  public V1Drill() {}",
			"  public V1Drill(List<Target> targets) { super(targets); }",
			"  @Override public void init() {}",
			"  @Override public void targetUpdate(Target target, TargetChange change) {}",
			"  @Override public ExerciseMetadata getInfo() {",
			"    return new ExerciseMetadata(\"V1 Drill\", \"1.1\", \"ShootOFF tests\", \"Loaded from a v1 jar\");",
			"  }",
			"  @Override public void shotListener(Shot shot, Optional<Hit> hit) {}",
			"  @Override public void reset(List<Target> targets) {}",
			"}");

	@TempDir Path temp;

	// ShootOFF's classes, as a plugin author compiles against them
	private static String classpath() {
		return String.join(File.pathSeparator, System.getProperty("java.class.path"), location(TrainingExerciseBase.class),
				location(Exercise.class), location(Shot.class));
	}

	private static String location(Class<?> type) {
		return new File(type.getProtectionDomain().getCodeSource().getLocation().getPath()).getPath();
	}

	@Test
	void v2JarLoadsAsAnExercise() throws Exception {
		final Path jar = PluginJars.build(temp, "v2.jar", Optional.of(PluginJars.descriptor(2, V2_CLASS)),
				Map.of(V2_CLASS, V2_SOURCE), classpath());

		final Plugin plugin = new Plugin(jar, ExerciseLoaders.all());

		assertEquals(2, plugin.getApiVersion());
		assertEquals(PluginType.PROJECTOR_ONLY, plugin.getType());
		final V2ExerciseEntry entry = assertInstanceOf(V2ExerciseEntry.class, plugin.getEntry());
		assertEquals("V2 Drill", entry.metadata().getName());
		final Exercise exercise = entry.newInstance();
		assertEquals(V2_CLASS, exercise.getClass().getName());
		assertSame(plugin.getLoader(), exercise.getClass().getClassLoader());
	}

	@Test
	void v1JarLoadsThroughTheLegacyPath() throws Exception {
		// Like the installed RandomTargetParDrill.jar: a descriptor without apiVersion
		final Path jar = PluginJars.build(temp, "v1.jar", Optional.of(PluginJars.descriptor(1, V1_CLASS)),
				Map.of(V1_CLASS, V1_SOURCE), classpath());

		final Plugin plugin = new Plugin(jar, ExerciseLoaders.all());

		assertEquals(1, plugin.getApiVersion());
		assertEquals(PluginType.PROJECTOR_ONLY, plugin.getType());
		final LegacyExerciseEntry entry = assertInstanceOf(LegacyExerciseEntry.class, plugin.getEntry());
		assertInstanceOf(ProjectorTrainingExerciseBase.class, entry.prototype());
		assertEquals("V1 Drill", entry.metadata().getName());
		assertEquals(V1_CLASS, entry.exerciseClass().getName());
	}

	@Test
	void descriptorOnTheClasspathIsIgnored() throws Exception {
		// ShootOFF's test resources put a shootoff.xml naming ShootForScore on the classpath
		assertNotNull(TestPluginLoading.class.getClassLoader().getResource("shootoff.xml"));
		final Path bare = PluginJars.build(temp, "bare.jar", Optional.empty(), Map.of(V2_CLASS, V2_SOURCE),
				classpath());

		assertThrows(IllegalArgumentException.class, () -> new Plugin(bare, ExerciseLoaders.all()));
	}

	@Test
	void v2ClassThatIsNotAnExerciseIsRejected() throws Exception {
		final Path jar = PluginJars.build(temp, "notexercise.jar",
				Optional.of(PluginJars.descriptor(2, "com.example.v2.NotAnExercise")),
				Map.of("com.example.v2.NotAnExercise", "package com.example.v2; public class NotAnExercise {}"),
				classpath());

		assertThrows(IllegalArgumentException.class, () -> new Plugin(jar, ExerciseLoaders.all()));
	}
}
