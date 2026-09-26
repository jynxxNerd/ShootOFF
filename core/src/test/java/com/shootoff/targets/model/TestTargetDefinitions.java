package com.shootoff.targets.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.shootoff.BundledFiles;
import com.shootoff.geom.Point;

class TestTargetDefinitions {
	@TempDir Path temp;

	private static TargetDefinition parse(String xml) throws TargetFormatException {
		return TargetDefinitions.load(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)),
				ResourceResolver.files());
	}

	@Test
	void everyBundledTargetParses() throws IOException, TargetFormatException {
		final List<Path> files = BundledFiles.targets();

		assertEquals(26, files.size());
		for (final Path file : files) {
			final TargetDefinition definition = TargetDefinitions.load(file);
			assertFalse(definition.regions().isEmpty(), file + " has no regions");
			assertEquals(Optional.of(file.toFile()), definition.file());
		}
	}

	@Test
	void regionsKeepFileOrderGeometryAndTags() throws TargetFormatException {
		final TargetDefinition definition = parse("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
				+ "<target>\n"
				+ "\t<image x=\"139.5\" y=\"1.5\" file=\"targets/duel_tree_stand.gif\">\n"
				+ "\t\t<tag name=\"subtarget\" value=\"stand\" />\n"
				+ "\t</image>\n"
				+ "\t<rectangle x=\"1\" y=\"2\" width=\"3\" height=\"4\" fill=\"black\">\n"
				+ "\t\t<tag name=\"visible\" value=\"false\" />\n"
				+ "\t\t<tag name=\"a\" value=\"b\" />\n"
				+ "\t</rectangle>\n"
				+ "\t<ellipse centerX=\"5\" centerY=\"6\" radiusX=\"7\" radiusY=\"8\" fill=\"#ff00ff\" />\n"
				+ "\t<polygon fill=\"red\">\n"
				+ "\t\t<point x=\"0\" y=\"0\" />\n"
				+ "\t\t<point x=\"10\" y=\"0\" />\n"
				+ "\t\t<point x=\"5\" y=\"9\" />\n"
				+ "\t</polygon>\n"
				+ "</target>\n");

		assertEquals(4, definition.regions().size());
		// duel_tree_stand.gif's logical screen is 209x589
		assertEquals(new ImageRegion(0, 139.5, 1.5, "targets/duel_tree_stand.gif", 209, 589,
				Map.of("subtarget", "stand")), definition.regions().get(0));
		final RectangleRegion rectangle = (RectangleRegion) definition.regions().get(1);
		assertEquals(new RectangleRegion(1, 1, 2, 3, 4, "black", Map.of("visible", "false", "a", "b")), rectangle);
		assertEquals(List.of("visible", "a"), List.copyOf(rectangle.tags().keySet()));
		assertFalse(rectangle.isVisibleByDefault());
		assertEquals(new EllipseRegion(2, 5, 6, 7, 8, "#ff00ff", Map.of()), definition.regions().get(2));
		assertEquals(new PolygonRegion(3, List.of(new Point(0, 0), new Point(10, 0), new Point(5, 9)), "red", Map.of()),
				definition.regions().get(3));
		assertEquals(Optional.empty(), definition.file());
	}

	@Test
	void commandsAreParsedFromTheCommandTag() throws TargetFormatException {
		final Region region = parse("<target><ellipse centerX=\"1\" centerY=\"1\" radiusX=\"1\" radiusY=\"1\" fill=\"red\">"
				+ "<tag name=\"command\" value=\"animate(pepper_popper);"
				+ "play_sound(sounds/steel_sound_1.wav,pepper_popper);reverse\" />"
				+ "</ellipse></target>").regions().get(0);

		assertEquals(List.of(new RegionCommand("animate", List.of("pepper_popper")),
				new RegionCommand("play_sound", List.of("sounds/steel_sound_1.wav", "pepper_popper")),
				new RegionCommand("reverse", List.of())), region.commands());
	}

	@Test
	void targetAttributesKeepFillCanvasAndPerception() throws TargetFormatException {
		final TargetDefinition definition = parse("<target fillCanvas=\"true\" defaultPerceivedWidth=\"450\" "
				+ "defaultPerceivedHeight=\"570\" defaultDistance=\"10000\">"
				+ "<rectangle x=\"0\" y=\"0\" width=\"1\" height=\"1\" fill=\"black\" /></target>");

		assertTrue(definition.fillsCanvas());
		assertEquals(Optional.of(new DefaultPerception(450, 570, 10000)), definition.defaultPerception());
		assertEquals(List.of("fillCanvas", "defaultPerceivedWidth", "defaultPerceivedHeight", "defaultDistance"),
				List.copyOf(definition.tags().keySet()));

		final TargetDefinition plain = parse(
				"<target><rectangle x=\"0\" y=\"0\" width=\"1\" height=\"1\" fill=\"black\" /></target>");
		assertFalse(plain.fillsCanvas());
		assertFalse(plain.defaultPerception().isPresent());
	}

	@Test
	void malformedXmlReportsFileAndLine() throws IOException {
		final Path file = temp.resolve("broken.target");
		Files.writeString(file, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<target>\n\t<ellipse centerX=\"1\"\n</target>\n");

		final TargetFormatException e = assertThrows(TargetFormatException.class, () -> TargetDefinitions.load(file));
		assertTrue(e.getMessage().matches(Pattern.quote(file.toString()) + ":\\d+: .+"), e.getMessage());
	}

	@Test
	void missingAttributeIsNamed() {
		final TargetFormatException e = assertThrows(TargetFormatException.class,
				() -> parse("<target>\n<ellipse centerX=\"1\" centerY=\"2\" radiusX=\"3\" fill=\"red\" />\n</target>"));
		assertEquals("target stream:2: <ellipse> is missing attribute radiusY", e.getMessage());
	}

	@Test
	void missingImageIsNamed() {
		final TargetFormatException e = assertThrows(TargetFormatException.class,
				() -> parse("<target>\n<image x=\"0\" y=\"0\" file=\"targets/no_such_image.png\" />\n</target>"));
		assertEquals("target stream:2: image targets/no_such_image.png not found", e.getMessage());
	}

	@Test
	void atPathsResolveThroughTheClassLoader() throws IOException, TargetFormatException {
		final Path jarRoot = temp.resolve("jar");
		final Path dir = Files.createDirectories(jarRoot.resolve("sub"));
		ImageIO.write(new BufferedImage(3, 2, BufferedImage.TYPE_INT_ARGB), "png", dir.resolve("dot.png").toFile());
		Files.writeString(dir.resolve("at.target"), "<target><image x=\"4\" y=\"5\" file=\"@sub/dot.png\" /></target>");

		try (URLClassLoader loader = new URLClassLoader(new URL[] { jarRoot.toUri().toURL() }, null)) {
			final TargetDefinition definition = TargetDefinitions.load(loader.getResourceAsStream("sub/at.target"),
					ResourceResolver.classLoader(loader));
			assertEquals(new ImageRegion(0, 4, 5, "@sub/dot.png", 3, 2, Map.of()), definition.regions().get(0));
		}

		// Without the exercise's class loader the image can't be found
		assertThrows(TargetFormatException.class, () -> TargetDefinitions
				.load(Files.newInputStream(dir.resolve("at.target")), ResourceResolver.files()));
	}

	@Test
	void writtenDefinitionParsesBackEqual() throws TargetFormatException {
		for (final String name : List.of("targets/Duel_Tree.target", "targets/IPSC.target",
				"targets/POI_Offset_Adjustment.target")) {
			final TargetDefinition original = TargetDefinitions.load(Paths.get(name));
			final File copy = temp.resolve("copy.target").toFile();

			TargetDefinitions.write(original, copy);
			final TargetDefinition reread = TargetDefinitions.load(copy.toPath());

			assertEquals(original.tags(), reread.tags(), name);
			assertEquals(original.regions(), reread.regions(), name);
		}
	}
}
