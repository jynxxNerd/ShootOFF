package com.shootoff.gui;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.util.Optional;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import com.shootoff.camera.CameraManager;
import com.shootoff.camera.CamerasSupervisor;
import com.shootoff.camera.MockCamera;
import com.shootoff.camera.Shot;
import com.shootoff.camera.shot.DisplayShot;
import com.shootoff.camera.shot.ScaledShot;
import com.shootoff.camera.shot.ShotColor;
import com.shootoff.config.Configuration;
import com.shootoff.config.ConfigurationException;
import com.shootoff.config.ScratchConfig;
import com.shootoff.geom.Rect;
import com.shootoff.gui.controller.ShootOFFController;
import com.shootoff.gui.targets.TargetView;
import com.shootoff.targets.Hit;
import com.shootoff.targets.Target;
import com.shootoff.targets.TargetRegion;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.Group;
import javafx.scene.shape.Shape;

public class TestCanvasManager {
	@Rule public JavaFXThreadingRule javafxRule = new JavaFXThreadingRule();

	private CanvasManager cm;
	private Target ipscTarget;
	private ObservableList<ShotEntry> shotEntries = FXCollections.observableArrayList();

	private Configuration config;
	private String workingTreeConfig;

	@Before
	public void setUp() throws ConfigurationException, IOException {
		System.setProperty("shootoff.home", System.getProperty("user.dir"));

		org.bytedeco.javacpp.Loader.load(org.bytedeco.opencv.opencv_java.class);

		workingTreeConfig = ScratchConfig.workingTreeFingerprint();
		// testPOIAdjust writes the configuration: never to the owner's shootoff.properties
		config = new Configuration(ScratchConfig.emptyFile().getPath(), new String[0]);
		CamerasSupervisor cs = new CamerasSupervisor(config);
		cm = new CanvasManager(new Group(), new ShootOFFController(), "test", shotEntries);
		CameraManager cameraManager = cs.addCameraManager(new MockCamera(), null, cm).get();
		cs.setDetectingAll(false);
		cm.setCameraManager(cameraManager);

		ipscTarget = cm.addTarget(new File("targets/IPSC.target")).get();
		ipscTarget.setPosition(0, 0);
	}

	@After
	public void checkTheWorkingTreeConfigIsUntouched() {
		assertEquals(workingTreeConfig, ScratchConfig.workingTreeFingerprint());
	}

	@Test
	public void testCheckHitMiss() {
		Optional<Hit> h = cm.checkHit(new DisplayShot(ShotColor.RED, 0, 0, 0, 2), Optional.empty(), false);

		assertFalse(h.isPresent());
	}

	@Test
	public void testCheckHitHit() {
		Optional<Hit> h = cm.checkHit(new DisplayShot(ShotColor.RED, 150, 150, 0, 2), Optional.empty(), false);

		assertTrue(h.isPresent());
		assertTrue(ipscTarget.getRegions().contains(h.get().getHitRegion()));
		assertEquals(ipscTarget, h.get().getTarget());
	}

	@Test
	public void testAddShotMissHitMiss() {
		Optional<Hit> h = cm.checkHit(new DisplayShot(ShotColor.RED, 0, 0, 0, 2), Optional.empty(), false);

		assertFalse(h.isPresent());

		h = cm.checkHit(new DisplayShot(ShotColor.RED, 150, 150, 0, 2), Optional.empty(), false);

		assertTrue(h.isPresent());
		assertTrue(ipscTarget.getRegions().contains(h.get().getHitRegion()));
		assertEquals(ipscTarget, h.get().getTarget());

		h = cm.checkHit(new DisplayShot(ShotColor.GREEN, 0, 0, 0, 2), Optional.empty(), false);

		assertFalse(h.isPresent());
	}

	// TODO: Add infrared?

	@Test
	public void testWebCodeRed() {
		assertEquals("#FF0000", CanvasManager.colorToWebCode(DisplayShot.toPaint(ShotColor.RED)));
	}

	@Test
	public void testWebCodeGreen() {
		assertEquals("#008000", CanvasManager.colorToWebCode(DisplayShot.toPaint(ShotColor.GREEN)));
	}

	@Test
	public void testRemoveTarget() {
		cm.removeTarget(ipscTarget);

		assertEquals(0, cm.getTargets().size());
	}

	// Spec §9, Revision 1: while the feed has a target, its camera looks at the whole frame
	@Test
	public void testTheCameraLooksAtTheWholeFrameOnlyWhileTheFeedHasTargets() {
		final CameraManager cameraManager = cm.getCameraManager();
		cameraManager.setLimitDetectProjection(true);
		cameraManager.setProjectionBounds(new Rect(100, 40, 200, 200));

		assertTrue(cm.hasTargets());
		assertEquals(Optional.empty(), cameraManager.getDetectionArea());

		cm.removeTarget(ipscTarget);

		assertFalse(cm.hasTargets());
		assertEquals(Optional.of(new Rect(100, 40, 200, 200)), cameraManager.getDetectionArea());
	}

	@Test
	public void testGetTargetGroups() {
		assertEquals(1, cm.getTargets().size());
		assertTrue(cm.getTargets().contains(ipscTarget));
	}

	@Test
	public void testTargetSelection() {
		Shape firstShape = (Shape) ipscTarget.getRegions().get(0);

		assertEquals(null, firstShape.getStroke());

		cm.toggleTargetSelection(Optional.of((TargetView) ipscTarget));

		assertEquals(TargetRegion.SELECTED_STROKE_COLOR, firstShape.getStroke());

		cm.toggleTargetSelection(Optional.empty());

		assertEquals(TargetRegion.UNSELECTED_STROKE_COLOR, firstShape.getStroke());
	}

	@Test
	public void testAddShot() {
		assertEquals(0, cm.getShots().size());

		DisplayShot shot = new DisplayShot(new Shot(ShotColor.RED, 0, 0, 0), 2);
		cm.addShot(shot, false);

		assertEquals(1, cm.getShots().size());
	}

	@Test
	public void testDisplayResolutionTranslationLarger() {
		config.setDisplayResolution(800, 600);
		cm.getCameraManager().setFeedResolution(640, 480);

		assertEquals(0, cm.getShots().size());

		cm.getCameraManager().injectShot(ShotColor.RED, 640, 480, true);

		// This sleep is to give the shot notification thread a chance
		// to do its job
		try {
			Thread.sleep(500);
		} catch (InterruptedException e) {}

		assertEquals(1, cm.getShots().size());

		assertEquals(800, cm.getShots().get(0).getX(), 1.0);
		assertEquals(600, cm.getShots().get(0).getY(), 1.0);
	}

	@Test
	public void testDisplayResolutionTranslationSmaller() {
		config.setDisplayResolution(320, 240);
		cm.getCameraManager().setFeedResolution(640, 480);

		assertEquals(0, cm.getShots().size());

		cm.getCameraManager().injectShot(ShotColor.RED, 640, 480, true);

		// This sleep is to give the shot notification thread a chance
		// to do its job
		try {
			Thread.sleep(500);
		} catch (InterruptedException e) {}

		assertEquals(1, cm.getShots().size());

		assertEquals(320, cm.getShots().get(0).getX(), 1.0);
		assertEquals(240, cm.getShots().get(0).getY(), 1.0);
	}

	@Test
	public void testClickToShoot() {
		config.setDisplayResolution(320, 240);

		assertEquals(0, cm.getShots().size());

		cm.getCameraManager().injectShot(ShotColor.RED, 320, 240, false);

		// This sleep is to give the shot notification thread a chance
		// to do its job
		try {
			Thread.sleep(500);
		} catch (InterruptedException e) {}

		assertEquals(1, cm.getShots().size());

		assertEquals(320, cm.getShots().get(0).getX(), 1.0);
		assertEquals(240, cm.getShots().get(0).getY(), 1.0);
	}

	@Test
	public void testPOIAdjust() {
		config.setDisplayResolution(640, 480);
		config.updatePOIAdjustment(-10.0, -10.0);
		config.updatePOIAdjustment(-10.0, -10.0);
		config.updatePOIAdjustment(-10.0, -10.0);
		config.updatePOIAdjustment(-10.0, -10.0);
		config.updatePOIAdjustment(-10.0, -10.0);

		assertEquals(0, cm.getShots().size());

		cm.getCameraManager().injectShot(ShotColor.RED, 160, 160, false);

		// This sleep is to give the shot notification thread a chance
		// to do its job
		try {
			Thread.sleep(500);
		} catch (InterruptedException e) {}

		assertEquals(1, cm.getShots().size());

		assertEquals(170, cm.getShots().get(0).getX(), 1.0);
		assertEquals(170, cm.getShots().get(0).getY(), 1.0);
		
		config.updatePOIAdjustment(-10.0, -10.0);
		
		cm.getCameraManager().reset();
		
		cm.getCameraManager().injectShot(ShotColor.RED, 160, 160, false);

		// This sleep is to give the shot notification thread a chance
		// to do its job
		try {
			Thread.sleep(500);
		} catch (InterruptedException e) {}

		assertEquals(1, cm.getShots().size());

		assertEquals(160, cm.getShots().get(0).getX(), 1.0);
		assertEquals(160, cm.getShots().get(0).getY(), 1.0);

	}

	@Test
	public void testDetectedShotBecomesMarkerAtItsDisplayPosition() {
		final ScaledShot detected = new ScaledShot(ShotColor.GREEN, 100, 50, 0);
		detected.setDisplayVals(1280, 960, 640, 480);

		cm.addShot(detected);

		final DisplayShot shown = cm.getShots().get(cm.getShots().size() - 1);
		assertEquals(200, shown.getMarker().getCenterX(), 0.1);
		assertEquals(100, shown.getMarker().getCenterY(), 0.1);
		assertEquals(config.getMarkerRadius(), shown.getMarker().getRadiusX(), 0.1);
		assertEquals(ShotColor.GREEN, shown.getColor());
	}

	@Test
	public void testDetectedShotKeepsCameraSpaceOrigAndBoundsWhenDisplayIsScaled() {
		// display scaled 2x relative to feed, no projection bounds
		final ScaledShot detected = new ScaledShot(ShotColor.GREEN, 100, 50, 0);
		detected.setDisplayVals(1280, 960, 640, 480);

		cm.addShot(detected);

		final DisplayShot shown = cm.getShots().get(cm.getShots().size() - 1);
		assertEquals(100, shown.getBoundsX(), 0.1);
		assertEquals(50, shown.getBoundsY(), 0.1);
		assertEquals(100, shown.getOrigX(), 0.1);
		assertEquals(50, shown.getOrigY(), 0.1);
		assertEquals(200, shown.getX(), 0.1);
		assertEquals(100, shown.getY(), 0.1);
	}

	@Test
	public void testDetectedShotWithBoundsKeepsCameraSpaceOrigAndBoundsWhenDisplayIsScaled() {
		// projection bounds offset the shot before display scaling is applied
		final ScaledShot detected = new ScaledShot(ShotColor.GREEN, 10, 10, 0);
		detected.adjustBounds(100, 40);
		detected.setDisplayVals(1280, 960, 640, 480);

		cm.addShot(detected);

		final DisplayShot shown = cm.getShots().get(cm.getShots().size() - 1);
		assertEquals(110, shown.getBoundsX(), 0.1);
		assertEquals(50, shown.getBoundsY(), 0.1);
		assertEquals(110, shown.getOrigX(), 0.1);
		assertEquals(50, shown.getOrigY(), 0.1);
		assertEquals(220, shown.getX(), 0.1);
		assertEquals(100, shown.getY(), 0.1);
	}

}
