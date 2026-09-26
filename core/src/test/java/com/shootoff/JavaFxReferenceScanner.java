package com.shootoff;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Finds compiled classes that mention JavaFX. Class files store referenced type names as text
 * in their constant pool (e.g. "Ljavafx/scene/Node;"), so a byte search is enough.
 */
final class JavaFxReferenceScanner {
	private JavaFxReferenceScanner() {}

	static List<String> findJavaFxReferences(Path classesDir) throws IOException {
		try (Stream<Path> files = Files.walk(classesDir)) {
			return files.filter(p -> p.toString().endsWith(".class")).filter(JavaFxReferenceScanner::referencesJavaFx)
					.map(p -> classesDir.relativize(p).toString().replace(File.separatorChar, '/')).sorted()
					.collect(Collectors.toList());
		}
	}

	static long countClasses(Path classesDir) throws IOException {
		try (Stream<Path> files = Files.walk(classesDir)) {
			return files.filter(p -> p.toString().endsWith(".class")).count();
		}
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
