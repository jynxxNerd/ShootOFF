/*
 * ShootOFF - Software for Laser Dry Fire Training
 * Copyright (C) 2016 phrack
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.shootoff.targets.io;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.shootoff.gui.controller.TargetEditorController;
import com.shootoff.targets.EllipseRegion;
import com.shootoff.targets.ImageRegion;
import com.shootoff.targets.PolygonRegion;
import com.shootoff.targets.RectangleRegion;
import com.shootoff.targets.TargetRegion;
import com.shootoff.targets.animation.GifAnimation;
import com.shootoff.targets.animation.SpriteAnimation;
import com.shootoff.targets.model.Region;
import com.shootoff.targets.model.ResourceResolver;
import com.shootoff.targets.model.TargetDefinition;
import com.shootoff.targets.model.TargetDefinitions;
import com.shootoff.targets.model.TargetFormatException;

import javafx.geometry.Point2D;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.paint.Color;
import javafx.scene.shape.Shape;

/**
 * Loads targets into JavaFX nodes (one v1 region node per model region, built from the core
 * target model) and saves the target editor's nodes.
 */
public class TargetIO {
	private static final Logger logger = LoggerFactory.getLogger(TargetIO.class);

	public static final double DEFAULT_OPACITY = 0.5;

	/**
	 * A target's model and the JavaFX nodes built from it: the group holds one region node per
	 * model region, in the same order.
	 */
	public static class TargetComponents {
		private final TargetDefinition definition;
		private final Group targetGroup;

		public TargetComponents(TargetDefinition definition, Group targetGroup) {
			this.definition = definition;
			this.targetGroup = targetGroup;
		}

		/**
		 * @param targetFile
		 *            the target's file, or <tt>null</tt>
		 * @return a target with no regions, e.g. a stand-in for a file that can't be loaded
		 */
		public static TargetComponents empty(File targetFile) {
			return new TargetComponents(new TargetDefinition(Optional.ofNullable(targetFile), Map.of(), List.of()),
					new Group());
		}

		public TargetDefinition getDefinition() {
			return definition;
		}

		/**
		 * @return the file the target was loaded from, or <tt>null</tt>
		 */
		public File getTargetFile() {
			return definition.file().orElse(null);
		}

		public Group getTargetGroup() {
			return targetGroup;
		}

		/**
		 * @return a modifiable copy of the target's tags
		 */
		public Map<String, String> getTargetTags() {
			return new HashMap<>(definition.tags());
		}

		public TargetComponents withTargetFile(File targetFile) {
			return new TargetComponents(definition.withFile(targetFile), targetGroup);
		}
	}

	public static void saveTarget(final Map<String, String> targetTags, final List<Node> regions,
			final File targetFile) {
		RegionVisitor visitor;

		if (targetFile.getName().endsWith("target")) {
			visitor = new XMLTargetWriter(targetFile);
		} else {
			logger.error("Unknown target file type.");
			return;
		}

		final URI baseURI = new File(System.getProperty("user.dir")).toURI();

		for (final Node node : regions) {
			final TargetRegion region = (TargetRegion) node;

			switch (region.getType()) {
			case IMAGE: {
				final ImageRegion img = (ImageRegion) node;

				// Make image path relative to cwd so that image files can be
				// found on different machines
				final URI imgURI = new File(img.getImageFile().getAbsolutePath()).toURI();
				final File relativeImageFile = new File(baseURI.relativize(imgURI).getPath());

				visitor.visitImageRegion(img.getBoundsInParent().getMinX(), img.getBoundsInParent().getMinY(),
						relativeImageFile, img.getAllTags());
			}
			break;
			case RECTANGLE: {
				final RectangleRegion rec = (RectangleRegion) node;
				visitor.visitRectangleRegion(rec.getBoundsInParent().getMinX(), rec.getBoundsInParent().getMinY(),
						rec.getWidth(), rec.getHeight(), TargetEditorController.getColorName((Color) rec.getFill()),
						rec.getAllTags());
			}
			break;
			case ELLIPSE: {
				final EllipseRegion ell = (EllipseRegion) node;
				final double absoluteCenterX = ell.getBoundsInParent().getMinX() + ell.getRadiusX();
				final double absoluteCenterY = ell.getBoundsInParent().getMinY() + ell.getRadiusY();
				visitor.visitEllipse(absoluteCenterX, absoluteCenterY, ell.getRadiusX(), ell.getRadiusY(),
						TargetEditorController.getColorName((Color) ell.getFill()), ell.getAllTags());
			}
			break;
			case POLYGON: {
				final PolygonRegion pol = (PolygonRegion) node;

				final Double[] points = new Double[pol.getPoints().size()];

				for (int i = 0; i < pol.getPoints().size(); i += 2) {
					final Point2D p = pol.localToParent(pol.getPoints().get(i), pol.getPoints().get(i + 1));

					points[i] = p.getX();
					points[i + 1] = p.getY();
				}

				visitor.visitPolygonRegion(points, TargetEditorController.getColorName((Color) pol.getFill()),
						pol.getAllTags());
			}
			break;
			}
		}

		visitor.visitEnd(targetTags);
	}

	public static Optional<TargetComponents> loadTarget(final File targetFile) {
		return loadTarget(targetFile, true);
	}

	/**
	 * @return why <tt>targetFile</tt> could not be loaded, naming the file, for a caller that
	 *         shows the reason to the user after {@link #loadTarget} has already returned an
	 *         empty result for it. Re-parses the file to recover the {@link TargetFormatException}
	 *         detail, which <tt>loadTarget</tt> only logs.
	 */
	public static String describeLoadFailure(final File targetFile) {
		try {
			TargetDefinitions.load(targetFile.toPath());
			return targetFile + ": could not be loaded";
		} catch (final TargetFormatException e) {
			return e.getMessage();
		}
	}

	// Used for loading targets from resource files for modular exercises
	public static Optional<TargetComponents> loadTarget(final InputStream targetStream, final ClassLoader loader) {
		return loadTarget(targetStream, true, loader);
	}

	public static Optional<TargetComponents> loadTarget(final File targetFile, boolean playAnimations) {
		if (!targetFile.getName().endsWith("target")) {
			logger.error("Unknown target file type.");
			return Optional.empty();
		}

		try {
			return Optional.of(buildTarget(TargetDefinitions.load(targetFile.toPath()), ResourceResolver.files(),
					playAnimations));
		} catch (TargetFormatException | IOException e) {
			logger.error("Can't load target: {}", e.getMessage());
			return Optional.empty();
		}
	}

	// Used for loading targets from resource files for modular exercises
	public static Optional<TargetComponents> loadTarget(final InputStream targetStream, boolean playAnimations,
			final ClassLoader loader) {
		final ResourceResolver resolver = ResourceResolver.classLoader(loader);

		try (InputStream in = targetStream) {
			return Optional.of(buildTarget(TargetDefinitions.load(in, resolver), resolver, playAnimations));
		} catch (TargetFormatException | IOException e) {
			logger.error("Can't load target from an exercise: {}", e.getMessage());
			return Optional.empty();
		}
	}

	/**
	 * Builds the JavaFX nodes for a target: one v1 region node per model region, in order, with
	 * the fill, tags, visibility and opacity the target file asks for.
	 *
	 * @param resolver
	 *            opens the target's images
	 * @param playAnimations
	 *            <tt>true</tt> to play each animated image once as the target appears
	 */
	public static TargetComponents buildTarget(TargetDefinition definition, ResourceResolver resolver,
			boolean playAnimations) throws IOException {
		final Group targetGroup = new Group();

		for (final Region region : definition.regions()) {
			final Node node = buildRegion(region, resolver, playAnimations);
			((TargetRegion) node).setTags(region.tags());

			if (!region.isVisibleByDefault()) node.setVisible(false);

			if (!(region instanceof com.shootoff.targets.model.ImageRegion)) {
				if (region.tag(Region.TAG_OPACITY).isPresent()) {
					node.setOpacity(Double.parseDouble(region.tag(Region.TAG_OPACITY).get()));
				} else {
					node.setOpacity(DEFAULT_OPACITY);
				}
			}

			targetGroup.getChildren().add(node);
		}

		return new TargetComponents(definition, targetGroup);
	}

	private static Node buildRegion(Region region, ResourceResolver resolver, boolean playAnimations)
			throws IOException {
		// Shape.setFill, not the region's own setFill(Color), which defers to the FX thread
		return switch (region) {
		case com.shootoff.targets.model.EllipseRegion e -> {
			final Shape ellipse = new EllipseRegion(e.centerX(), e.centerY(), e.radiusX(), e.radiusY());
			ellipse.setFill(TargetEditorController.createColor(e.fill()));
			yield ellipse;
		}
		case com.shootoff.targets.model.RectangleRegion r -> {
			final Shape rectangle = new RectangleRegion(r.x(), r.y(), r.width(), r.height());
			rectangle.setFill(TargetEditorController.createColor(r.fill()));
			yield rectangle;
		}
		case com.shootoff.targets.model.PolygonRegion p -> {
			final double[] points = new double[p.points().size() * 2];
			for (int i = 0; i < p.points().size(); i++) {
				points[2 * i] = p.points().get(i).getX();
				points[2 * i + 1] = p.points().get(i).getY();
			}

			final Shape polygon = new PolygonRegion(points);
			polygon.setFill(TargetEditorController.createColor(p.fill()));
			yield polygon;
		}
		case com.shootoff.targets.model.ImageRegion i -> buildImage(i, resolver, playAnimations);
		};
	}

	private static ImageRegion buildImage(com.shootoff.targets.model.ImageRegion region, ResourceResolver resolver,
			boolean playAnimations) throws IOException {
		final String path = region.imagePath();
		final File savedFile = new File(path);

		final File imageFile;
		if (savedFile.isAbsolute() || '@' == path.charAt(0)) {
			imageFile = savedFile;
		} else {
			imageFile = new File(System.getProperty("shootoff.home") + File.separator + path);
		}

		final ImageRegion imageRegion;
		try (InputStream imageStream = open(resolver, path)) {
			imageRegion = new ImageRegion(region.x(), region.y(), imageFile, imageStream);
		}

		final int firstDot = imageFile.getName().indexOf('.') + 1;
		if (imageFile.getName().substring(firstDot).endsWith("gif")) {
			try (InputStream gifStream = open(resolver, path)) {
				final GifAnimation gif = new GifAnimation(imageRegion, gifStream);
				imageRegion.setImage(gif.getFirstFrame());
				if (gif.getFrameCount() > 1) imageRegion.setAnimation(gif);
			} catch (final IOException e) {
				logger.error("Error reading animation from XML target", e);
			}

			if (imageRegion.getAnimation().isPresent() && playAnimations) {
				final SpriteAnimation animation = imageRegion.getAnimation().get();
				animation.setCycleCount(1);

				animation.setOnFinished((e) -> {
					animation.reset();
					animation.setOnFinished(null);
				});

				animation.play();
			}
		}

		return imageRegion;
	}

	private static InputStream open(ResourceResolver resolver, String path) throws IOException {
		return resolver.open(path).orElseThrow(() -> new FileNotFoundException(path));
	}
}
