package com.shootoff.targets;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Optional;

import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.image.Image;

/**
 * The JavaFX hit test the app used before hits came from the core model (TargetView.isHit up to
 * Plan 2 Task 6), kept so TestHitParity can keep comparing the model with JavaFX geometry.
 */
final class LegacyFxHitTest {
	record FxHit(int regionIndex, int impactX, int impactY) {}

	private LegacyFxHitTest() {}

	static Optional<FxHit> hit(Group targetGroup, double x, double y) {
		if (targetGroup.getBoundsInParent().contains(x, y)) {
			// Target was hit, see if a specific region was hit
			for (int i = targetGroup.getChildren().size() - 1; i >= 0; i--) {
				final Node node = targetGroup.getChildren().get(i);

				if (!(node instanceof TargetRegion)) continue;

				final Bounds nodeBounds = targetGroup.getLocalToParentTransform().transform(node.getBoundsInParent());

				final int adjustedX = (int) (x - nodeBounds.getMinX());
				final int adjustedY = (int) (y - nodeBounds.getMinY());

				if (nodeBounds.contains(x, y)) {
					final TargetRegion region = (TargetRegion) node;

					// Ignore regions where ignoreHit tag is true
					if (region.tagExists(Target.TAG_IGNORE_HIT)
							&& Boolean.parseBoolean(region.getTag(Target.TAG_IGNORE_HIT)))
						continue;

					if (region.getType() == RegionType.IMAGE) {
						final Image currentImage = ((ImageRegion) region).getImage();

						if (adjustedX < 0 || adjustedY < 0) return Optional.empty();

						if (Math.abs(currentImage.getWidth() - nodeBounds.getWidth()) > .0000001
								|| Math.abs(currentImage.getHeight() - nodeBounds.getHeight()) > .0000001) {

							final BufferedImage bufferedOriginal = SwingFXUtils.fromFXImage(currentImage, null);

							final java.awt.Image tmp = bufferedOriginal.getScaledInstance((int) nodeBounds.getWidth(),
									(int) nodeBounds.getHeight(), java.awt.Image.SCALE_SMOOTH);
							final BufferedImage bufferedResized = new BufferedImage((int) nodeBounds.getWidth(),
									(int) nodeBounds.getHeight(), BufferedImage.TYPE_INT_ARGB);

							final Graphics2D g2d = bufferedResized.createGraphics();
							g2d.drawImage(tmp, 0, 0, null);
							g2d.dispose();

							if (adjustedX >= bufferedResized.getWidth() || adjustedY >= bufferedResized.getHeight()
									|| bufferedResized.getRGB(adjustedX, adjustedY) >> 24 == 0) {
								continue;
							}
						} else {
							if (adjustedX >= currentImage.getWidth() || adjustedY >= currentImage.getHeight()
									|| currentImage.getPixelReader().getArgb(adjustedX, adjustedY) >> 24 == 0) {
								continue;
							}
						}
					} else {
						final Point2D localCoords = targetGroup.parentToLocal(x, y);
						if (!node.contains(localCoords)) continue;
					}

					return Optional.of(new FxHit(i, adjustedX, adjustedY));
				}
			}
		}

		return Optional.empty();
	}
}
