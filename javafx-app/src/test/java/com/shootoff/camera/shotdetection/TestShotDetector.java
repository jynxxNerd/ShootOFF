package com.shootoff.camera.shotdetection;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.bytedeco.javacpp.Loader;
import org.bytedeco.opencv.opencv_java;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.shootoff.camera.CameraManager;
import com.shootoff.camera.MockCamera;
import com.shootoff.camera.RecordingCameraView;
import com.shootoff.camera.shot.ScaledShot;
import com.shootoff.camera.shot.ShotColor;
import com.shootoff.config.Configuration;
import com.shootoff.config.ConfigurationException;
import com.shootoff.geom.Rect;

class TestShotDetector {
	private Configuration config;
	private RecordingCameraView view;
	private CameraManager cameraManager;

	@BeforeAll
	static void loadOpenCv() {
		Loader.load(opencv_java.class);
	}

	@BeforeEach
	void setUp() throws ConfigurationException {
		config = new Configuration(new String[0]);
		view = new RecordingCameraView();
		// MockCamera is never opened, so the feed stays at the default 640x480
		cameraManager = new CameraManager(new MockCamera(), null, view);
	}

	private ScaledShot nextShot() throws InterruptedException {
		return view.awaitShot(5, TimeUnit.SECONDS).orElseThrow(() -> new AssertionError("no shot reached the view"));
	}

	@Test
	void detectedShotIsScaledToTheDisplay() throws InterruptedException {
		config.setDisplayResolution(1280, 960);

		cameraManager.injectShot(ShotColor.GREEN, 100, 50, true);

		final ScaledShot shot = nextShot();
		// Plain data: markers are the view's business
		assertEquals(ScaledShot.class, shot.getClass());
		assertEquals(ShotColor.GREEN, shot.getColor());
		assertEquals(200, shot.getX(), 0.001);
		assertEquals(100, shot.getY(), 0.001);
		assertEquals(100, shot.getBoundsX(), 0.001);
	}

	@Test
	void clickToShootShotIsNotScaled() throws InterruptedException {
		config.setDisplayResolution(1280, 960);

		cameraManager.injectShot(ShotColor.RED, 100, 50, false);

		final ScaledShot shot = nextShot();
		assertEquals(100, shot.getX(), 0.001);
		assertEquals(50, shot.getY(), 0.001);
	}

	@Test
	void ignoredLaserColorIsDropped() throws InterruptedException {
		config.setIgnoreLaserColor(true);
		config.setIgnoreLaserColorName("green");

		cameraManager.injectShot(ShotColor.GREEN, 100, 50, true);
		assertEquals(Optional.empty(), view.awaitShot(500, TimeUnit.MILLISECONDS));

		cameraManager.injectShot(ShotColor.RED, 300, 300, true);
		assertEquals(ShotColor.RED, nextShot().getColor());
	}

	@Test
	void shotInsideProjectionIsOffsetByTheProjectionOrigin() throws InterruptedException {
		cameraManager.setLimitDetectProjection(true);
		cameraManager.setProjectionBounds(new Rect(100, 40, 200, 200));

		// JavaShotDetector sees only the projection sub-image, so its coordinates are relative to it
		cameraManager.injectShot(ShotColor.GREEN, 10, 10, true);

		final ScaledShot shot = nextShot();
		assertEquals(110, shot.getX(), 0.001);
		assertEquals(50, shot.getY(), 0.001);
		assertEquals(10, shot.getOrigX(), 0.001);
	}
}
