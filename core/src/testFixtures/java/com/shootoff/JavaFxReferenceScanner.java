package com.shootoff;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Finds compiled classes that mention JavaFX. Class files store referenced type names as text
 * in their constant pool (e.g. "Ljavafx/scene/Node;"), so a byte search is enough.
 */
public final class JavaFxReferenceScanner {
	private JavaFxReferenceScanner() {}

	public static List<String> findJavaFxReferences(Path classesDir) throws IOException {
		try (Stream<Path> files = Files.walk(classesDir)) {
			return files.filter(p -> p.toString().endsWith(".class")).filter(JavaFxReferenceScanner::referencesJavaFx)
					.map(p -> classesDir.relativize(p).toString().replace(File.separatorChar, '/')).sorted()
					.collect(Collectors.toList());
		}
	}

	public static long countClasses(Path classesDir) throws IOException {
		try (Stream<Path> files = Files.walk(classesDir)) {
			return files.filter(p -> p.toString().endsWith(".class")).count();
		}
	}

	/**
	 * Scans the classes folder or jar that <tt>anchor</tt> was loaded from. Under java-test-fixtures,
	 * a module's tests see its jar rather than its classes folder.
	 *
	 * @return the classes there that mention JavaFX
	 */
	public static List<String> findJavaFxReferencesNextTo(Class<?> anchor) throws Exception {
		final Path location = Paths.get(anchor.getProtectionDomain().getCodeSource().getLocation().toURI());
		final String anchorFile = anchor.getName().replace('.', '/') + ".class";

		if (Files.isDirectory(location)) return scanRoot(location, anchorFile);

		try (FileSystem jar = FileSystems.newFileSystem(location)) {
			return scanRoot(jar.getPath("/"), anchorFile);
		}
	}

	private static List<String> scanRoot(Path root, String anchorFile) throws IOException {
		if (!Files.exists(root.resolve(anchorFile))) {
			throw new IllegalStateException("Scanning the wrong place: " + root + " has no " + anchorFile);
		}
		return findJavaFxReferences(root);
	}

	private static boolean referencesJavaFx(Path classFile) {
		try {
			final String contents = new String(Files.readAllBytes(classFile), StandardCharsets.ISO_8859_1);
			return contents.contains("javafx/") || contents.contains("javafx.");
		} catch (final IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
