package com.shootoff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.shootoff.geom.Rect;

class TestNoJavaFxInCore {
	@Test
	void coreClassesDoNotReferenceJavaFx() throws Exception {
		final Path classesDir = Paths.get(Rect.class.getProtectionDomain().getCodeSource().getLocation().toURI());

		assertTrue(Files.isDirectory(classesDir), "Expected core's compiled classes folder, got " + classesDir);
		assertTrue(Files.exists(classesDir.resolve("com/shootoff/geom/Rect.class")),
				"Scanning the wrong folder: " + classesDir);
		assertTrue(JavaFxReferenceScanner.countClasses(classesDir) > 0);
		assertEquals(List.of(), JavaFxReferenceScanner.findJavaFxReferences(classesDir));
	}

	@Test
	void scannerFindsJavaFxTypeReference(@TempDir Path dir) throws Exception {
		Files.createDirectories(dir.resolve("a"));
		Files.write(dir.resolve("a/B.class"), "Êþº¾ Ljavafx/scene/Node; java/lang/Object"
				.getBytes(StandardCharsets.ISO_8859_1));
		Files.write(dir.resolve("a/C.class"), "Êþº¾ java/lang/Object".getBytes(StandardCharsets.ISO_8859_1));

		assertEquals(List.of("a/B.class"), JavaFxReferenceScanner.findJavaFxReferences(dir));
	}

	@Test
	void scannerFindsJavaFxNameInStringConstant(@TempDir Path dir) throws Exception {
		Files.write(dir.resolve("D.class"), "Class.forName javafx.application.Platform".getBytes(StandardCharsets.ISO_8859_1));

		assertEquals(List.of("D.class"), JavaFxReferenceScanner.findJavaFxReferences(dir));
	}
}
