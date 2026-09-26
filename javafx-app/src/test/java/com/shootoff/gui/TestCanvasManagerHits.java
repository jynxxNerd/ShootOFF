package com.shootoff.gui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.util.List;
import java.util.Optional;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import com.shootoff.camera.shot.DisplayShot;
import com.shootoff.camera.shot.ShotColor;
import com.shootoff.config.Configuration;
import com.shootoff.config.ConfigurationException;
import com.shootoff.geom.Rect;
import com.shootoff.gui.targets.TargetView;
import com.shootoff.targets.Hit;
import com.shootoff.targets.ImageRegion;
import com.shootoff.targets.animation.SpriteAnimation;
import com.shootoff.targets.io.TargetIO;
import com.shootoff.targets.model.HitTester;
import com.shootoff.targets.model.PlacedTarget;

import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.image.Image;

public class TestCanvasManagerHits {
	@Rule public JavaFXThreadingRule javafxRule = new JavaFXThreadingRule();

	private MockCanvasManager canvas;

	@Before
	public void setUp() throws ConfigurationException {
		System.setProperty("shootoff.home", System.getProperty("user.dir"));
		canvas = new MockCanvasManager(new Configuration(new String[0]));
	}

	private TargetView add(String targetFile) {
		return (TargetView) canvas
				.addTarget(new TargetView(TargetIO.loadTarget(new File(targetFile), false).get(), canvas, true));
	}

	private Optional<Hit> shoot(double x, double y) {
		return canvas.checkHit(new DisplayShot(ShotColor.RED, x, y, 0, 2), Optional.empty(), false);
	}

	private int regionHitAt(TargetView target, double x, double y) {
		return shoot(x, y).map(hit -> target.getRegions().indexOf(hit.getHitRegion())).orElse(-1);
	}

	@Test
	public void hitsComeFromTheModelWithTheLegacyImpactOffsets() {
		final TargetView ipsc = add("targets/IPSC.target");
		ipsc.setPosition(40, 30);
		final double x = 190.37;
		final double y = 180.61;

		final Optional<Hit> hit = shoot(x, y);
		final com.shootoff.targets.model.Hit modelHit = HitTester.hit(canvas.getTargetSet(), x, y).get();

		assertTrue(hit.isPresent());
		assertSame(ipsc, hit.get().getTarget());
		assertSame(ipsc.getRegions().get(modelHit.region().index()), hit.get().getHitRegion());

		// The impact is relative to the hit region's bounds on the canvas, as before
		final Node regionNode = (Node) hit.get().getHitRegion();
		final Bounds regionBounds = ipsc.getTargetGroup().getLocalToParentTransform()
				.transform(regionNode.getBoundsInParent());
		assertEquals((int) (x - regionBounds.getMinX()), hit.get().getImpactX());
		assertEquals((int) (y - regionBounds.getMinY()), hit.get().getImpactY());
	}

	@Test
	public void v1HitsCarryTheModelHit() {
		final TargetView ipsc = add("targets/IPSC.target");
		ipsc.setPosition(40, 30);

		final Hit hit = shoot(190.37, 180.61).get();

		assertEquals(HitTester.hit(canvas.getTargetSet(), 190.37, 180.61), hit.getModelHit());
		// A v1 hit made without the model (for example by a test) has none
		assertEquals(Optional.empty(), new Hit(ipsc, ipsc.getRegions().get(0), 0, 0).getModelHit());
	}

	@Test
	public void hiddenTargetsTakeNoShots() {
		final TargetView ipsc = add("targets/IPSC.target");
		assertTrue(shoot(150.37, 150.61).isPresent());

		ipsc.setVisible(false);

		assertFalse(shoot(150.37, 150.61).isPresent());
		// The v1 per-target test still ignores visibility
		assertTrue(ipsc.isHit(150.37, 150.61).isPresent());
	}

	@Test
	public void imageHitsFollowTheCurrentAnimationFrame() {
		for (final String targetFile : List.of("targets/Pepper_Popper.target",
				"targets/IPSC_Classic_Falling_Popper.target")) {
			final TargetView target = add(targetFile);
			final PlacedTarget placed = target.getPlacedTarget();

			for (int i = 0; i < target.getRegions().size(); i++) {
				if (!(target.getRegions().get(i) instanceof ImageRegion image) || image.getAnimation().isEmpty())
					continue;

				final SpriteAnimation animation = image.getAnimation().get();
				final Image first = animation.getFrame(0);
				final Image last = animation.getFrame(animation.getFrameCount() - 1);
				final Optional<double[]> point = pointOnlyTheFirstFrameCovers(placed, i, first, last);
				if (point.isEmpty()) continue;

				final double x = point.get()[0];
				final double y = point.get()[1];
				assertEquals(i, regionHitAt(target, x, y));

				// The popper falls: where it stood is now transparent
				image.setImage(last);
				assertNotEquals(i, regionHitAt(target, x, y));

				// And stands again after a reset
				image.setImage(first);
				assertEquals(i, regionHitAt(target, x, y));
				return;
			}

			canvas.removeTarget(target);
		}

		fail("No animated region has a pixel that only its first frame covers");
	}

	// A canvas point on an opaque pixel of the first frame that is transparent in the last, and
	// that no other region of the target covers
	private static Optional<double[]> pointOnlyTheFirstFrameCovers(PlacedTarget placed, int regionIndex, Image first,
			Image last) {
		final Rect bounds = placed.regionBounds(placed.getDefinition().regions().get(regionIndex));

		for (int py = 0; py < (int) first.getHeight(); py++) {
			for (int px = 0; px < (int) first.getWidth(); px++) {
				if ((first.getPixelReader().getArgb(px, py) >>> 24) == 0) continue;
				if ((last.getPixelReader().getArgb(px, py) >>> 24) != 0) continue;

				final double x = bounds.getMinX() + px + 0.5;
				final double y = bounds.getMinY() + py + 0.5;

				final boolean coveredByAnother = placed.getDefinition().regions().stream()
						.filter(region -> region.index() != regionIndex)
						.anyMatch(region -> placed.regionBounds(region).contains(x, y));
				if (!coveredByAnother) return Optional.of(new double[] { x, y });
			}
		}

		return Optional.empty();
	}
}
