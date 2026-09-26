package com.shootoff.plugins.engine;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;

import javax.tools.ToolProvider;

/**
 * Builds plugin jars for tests, the way a plugin author's build would.
 */
public final class PluginJars {
	private PluginJars() {}

	/**
	 * @return a <tt>shootoff.xml</tt> naming <tt>exerciseClass</tt>; an <tt>apiVersion</tt> of 1 is
	 *         left out, as v1 plugins do
	 */
	public static String descriptor(int apiVersion, String exerciseClass) {
		final String version = apiVersion == 1 ? "" : " apiVersion=\"" + apiVersion + "\"";
		return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<shootoffExercise" + version + " exerciseClass=\""
				+ exerciseClass + "\" />\n";
	}

	/**
	 * Compiles <tt>sources</tt> (class name to source code) against <tt>classpath</tt> and jars the
	 * classes as <tt>dir/jarName</tt>, with <tt>descriptor</tt> as its <tt>shootoff.xml</tt> if present.
	 * The build files go in <tt>dir/jarName.build</tt>.
	 */
	public static Path build(Path dir, String jarName, Optional<String> descriptor, Map<String, String> sources,
			String classpath) throws IOException {
		final Path work = Files.createDirectories(dir.resolve(jarName + ".build"));
		final Path classes = Files.createDirectories(work.resolve("classes"));

		if (!sources.isEmpty()) {
			final List<String> args = new ArrayList<>(List.of("-classpath", classpath, "-d", classes.toString()));
			for (final Map.Entry<String, String> source : sources.entrySet()) {
				final Path file = work.resolve("src").resolve(source.getKey().replace('.', '/') + ".java");
				Files.createDirectories(file.getParent());
				Files.writeString(file, source.getValue());
				args.add(file.toString());
			}

			if (ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(new String[0])) != 0) {
				throw new IllegalStateException("The test plugin's sources didn't compile");
			}
		}

		final Manifest manifest = new Manifest();
		manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");

		final Path jar = dir.resolve(jarName);
		try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), manifest);
				Stream<Path> files = Files.walk(classes)) {
			if (descriptor.isPresent()) add(out, "shootoff.xml", descriptor.get().getBytes(StandardCharsets.UTF_8));

			for (final Path file : files.filter(Files::isRegularFile).sorted().toList()) {
				add(out, classes.relativize(file).toString().replace(File.separatorChar, '/'), Files.readAllBytes(file));
			}
		}

		return jar;
	}

	private static void add(JarOutputStream out, String name, byte[] contents) throws IOException {
		out.putNextEntry(new JarEntry(name));
		out.write(contents);
		out.closeEntry();
	}
}
