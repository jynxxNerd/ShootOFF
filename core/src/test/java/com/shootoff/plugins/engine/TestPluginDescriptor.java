package com.shootoff.plugins.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestPluginDescriptor {
	@TempDir Path temp;

	private PluginDescriptor read(String name, Optional<String> descriptor, ClassLoader parent) throws IOException {
		final Path jar = PluginJars.build(temp, name, descriptor, Map.of(), "");
		try (URLClassLoader loader = new URLClassLoader(new URL[] { jar.toUri().toURL() }, parent)) {
			return PluginDescriptor.read(loader, jar);
		}
	}

	@Test
	void apiVersionDefaultsToOne() throws IOException {
		assertEquals(new PluginDescriptor(1, "a.Drill"),
				read("v1.jar", Optional.of(PluginJars.descriptor(1, "a.Drill")), null));
	}

	@Test
	void apiVersionTwoIsRead() throws IOException {
		assertEquals(new PluginDescriptor(2, "a.Drill"),
				read("v2.jar", Optional.of(PluginJars.descriptor(2, "a.Drill")), null));
	}

	@Test
	void nonNumericApiVersionIsRejected() {
		assertThrows(IllegalArgumentException.class, () -> read("bad.jar",
				Optional.of("<shootoffExercise apiVersion=\"two\" exerciseClass=\"a.Drill\" />"), null));
	}

	@Test
	void missingExerciseClassIsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> read("noclass.jar", Optional.of("<shootoffExercise apiVersion=\"2\" />"), null));
	}

	@Test
	void descriptorOnTheParentClassPathIsIgnored() throws IOException {
		final Path classpath = Files.createDirectories(temp.resolve("classpath"));
		Files.writeString(classpath.resolve("shootoff.xml"), PluginJars.descriptor(1, "com.shootoff.plugins.ShootForScore"));

		try (URLClassLoader parent = new URLClassLoader(new URL[] { classpath.toUri().toURL() }, null)) {
			// getResource would find the parent's descriptor; the plugin's own jar has none
			assertNotNull(parent.getResource("shootoff.xml"));
			assertThrows(IllegalArgumentException.class, () -> read("bare.jar", Optional.empty(), parent));
		}
	}
}
