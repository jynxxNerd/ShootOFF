package com.shootoff.targets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import com.shootoff.geom.Rect;
import com.shootoff.gui.JavaFXThreadingRule;
import com.shootoff.gui.targets.FxAlphaMasks;
import com.shootoff.targets.io.TargetIO;
import com.shootoff.targets.model.HitTester;
import com.shootoff.targets.model.PlacedTarget;
import com.shootoff.targets.model.Placement;
import com.shootoff.targets.model.TargetDefinitions;
import com.shootoff.targets.model.TargetFormatException;
import com.shootoff.targets.model.TargetSet;

import javafx.scene.Group;
import javafx.scene.Node;

/**
 * Hit-testing parity (spec §8). For every bundled target, over a grid of points across its bounds
 * at three placements, the core HitTester must find the same region as the JavaFX hit test the app
 * used before (LegacyFxHitTest), with the same impact offsets. The JavaFX results are recorded in
 * hit-parity.txt, which keeps checking the model after the JavaFX hit path is gone.
 */
public class TestHitParity {
	@Rule public JavaFXThreadingRule javafxRule = new JavaFXThreadingRule();

	private static final Path EXPECTATIONS = Paths.get("javafx-app/src/test/resources/targets/hit-parity.txt");
	private static final String RECORD_VARIABLE = "SHOOTOFF_RECORD_HIT_PARITY";

	// Unscaled at the origin; shrunk and moved; stretched unevenly from a negative position
	private static final Placement[] PLACEMENTS = { new Placement(0, 0, 1, 1, true),
			new Placement(37.25, 81.5, 0.63, 0.63, true), new Placement(-12.75, 20.125, 1.71, 0.88, true) };
	// Points per row and per column for each placement
	private static final int[] GRID = { 16, 12, 12 };
	// How far past the target's bounds the grid reaches
	private static final double MARGIN = 3;

	@Before
	public void setUp() {
		System.setProperty("shootoff.home", System.getProperty("user.dir"));
	}

	@Test
	public void modelMatchesJavaFxForEveryBundledTarget() throws IOException, TargetFormatException {
		final List<String> mismatches = new ArrayList<>();
		final List<String> recorded = new ArrayList<>();

		for (final Path file : bundledTargets()) {
			for (int p = 0; p < PLACEMENTS.length; p++) {
				final Placement placement = PLACEMENTS[p];
				final Group group = TargetIO.loadTarget(file.toFile(), false).get().getTargetGroup();
				group.setLayoutX(placement.x());
				group.setLayoutY(placement.y());
				group.setScaleX(placement.scaleX());
				group.setScaleY(placement.scaleY());
				final PlacedTarget placed = place(file, group, placement);

				for (int row = 0; row < GRID[p]; row++) {
					final StringBuilder line = new StringBuilder(key(file, p, row)).append('|');

					for (int col = 0; col < GRID[p]; col++) {
						final double x = gridX(placed, p, col);
						final double y = gridY(placed, p, row);
						final Optional<LegacyFxHitTest.FxHit> fx = LegacyFxHitTest.hit(group, x, y);
						final Optional<com.shootoff.targets.model.Hit> model = HitTester.hit(placed, x, y);
						final int fxRegion = fx.map(LegacyFxHitTest.FxHit::regionIndex).orElse(-1);
						final int modelRegion = model.map(hit -> hit.region().index()).orElse(-1);

						if (fxRegion != modelRegion) {
							mismatches.add(String.format("%s at (%.6f, %.6f): JavaFX region %d, model region %d",
									key(file, p, row), x, y, fxRegion, modelRegion));
						} else if (fx.isPresent()) {
							final Rect bounds = placed.regionBounds(model.get().region());
							final int impactX = (int) (x - bounds.getMinX());
							final int impactY = (int) (y - bounds.getMinY());

							if (impactX != fx.get().impactX() || impactY != fx.get().impactY()) {
								mismatches.add(String.format("%s at (%.6f, %.6f): JavaFX impact (%d, %d), model (%d, %d)",
										key(file, p, row), x, y, fx.get().impactX(), fx.get().impactY(), impactX,
										impactY));
							}
						}

						if (col > 0) line.append(' ');
						line.append(fxRegion);
					}

					recorded.add(line.toString());
				}
			}
		}

		if (System.getenv(RECORD_VARIABLE) != null) {
			final List<String> lines = new ArrayList<>();
			lines.add("# Hit-parity expectations recorded from the JavaFX hit test by TestHitParity.");
			lines.add("# target|placement|row|region index hit at each grid column (-1 = miss). Never edit by hand.");
			lines.addAll(recorded);
			Files.createDirectories(EXPECTATIONS.getParent());
			Files.write(EXPECTATIONS, lines, StandardCharsets.UTF_8);
		}

		assertTrue(mismatches.size() + " mismatches:\n"
				+ mismatches.stream().limit(20).collect(Collectors.joining("\n")), mismatches.isEmpty());
	}

	@Test
	public void modelMatchesRecordedExpectations() throws IOException, TargetFormatException {
		final Map<String, String> expected = new LinkedHashMap<>();
		for (final String line : Files.readAllLines(EXPECTATIONS, StandardCharsets.UTF_8)) {
			if (line.isEmpty() || line.startsWith("#")) continue;

			final int split = line.lastIndexOf('|');
			expected.put(line.substring(0, split), line.substring(split + 1));
		}

		final List<String> mismatches = new ArrayList<>();
		final List<String> keys = new ArrayList<>();

		for (final Path file : bundledTargets()) {
			for (int p = 0; p < PLACEMENTS.length; p++) {
				final Group group = TargetIO.loadTarget(file.toFile(), false).get().getTargetGroup();
				final PlacedTarget placed = place(file, group, PLACEMENTS[p]);

				for (int row = 0; row < GRID[p]; row++) {
					final String key = key(file, p, row);
					keys.add(key);

					final StringBuilder actual = new StringBuilder();
					for (int col = 0; col < GRID[p]; col++) {
						final int region = HitTester.hit(placed, gridX(placed, p, col), gridY(placed, p, row))
								.map(hit -> hit.region().index()).orElse(-1);
						if (col > 0) actual.append(' ');
						actual.append(region);
					}

					if (!actual.toString().equals(expected.get(key))) {
						mismatches.add(key + ": recorded " + expected.get(key) + ", model " + actual);
					}
				}
			}
		}

		assertEquals("rows in hit-parity.txt", new HashSet<>(keys), new HashSet<>(expected.keySet()));
		assertTrue(mismatches.size() + " mismatches:\n"
				+ mismatches.stream().limit(20).collect(Collectors.joining("\n")), mismatches.isEmpty());
	}

	private static List<Path> bundledTargets() throws IOException {
		try (Stream<Path> paths = Files.walk(Paths.get("targets"))) {
			return paths.filter(path -> path.toString().endsWith(".target")).sorted().collect(Collectors.toList());
		}
	}

	private static String key(Path file, int placement, int row) {
		return file.toString().replace(File.separatorChar, '/') + "|" + placement + "|" + row;
	}

	// The model of the target in group, placed the same way, with each image region's current
	// frame as its alpha mask
	private static PlacedTarget place(Path file, Group group, Placement placement) throws TargetFormatException {
		final TargetSet targets = new TargetSet();
		final PlacedTarget placed = targets.add(TargetDefinitions.load(file), placement);

		for (int i = 0; i < group.getChildren().size(); i++) {
			final Node node = group.getChildren().get(i);
			if (node instanceof ImageRegion image) {
				targets.setImageMask(placed.getId(), i, FxAlphaMasks.of(image.getImage()));
			}
		}

		return placed;
	}

	private static double gridX(PlacedTarget placed, int placement, int col) {
		final Rect bounds = placed.getBounds();
		return bounds.getMinX() - MARGIN + (bounds.getWidth() + 2 * MARGIN) * (col + 0.5) / GRID[placement];
	}

	private static double gridY(PlacedTarget placed, int placement, int row) {
		final Rect bounds = placed.getBounds();
		return bounds.getMinY() - MARGIN + (bounds.getHeight() + 2 * MARGIN) * (row + 0.5) / GRID[placement];
	}
}
