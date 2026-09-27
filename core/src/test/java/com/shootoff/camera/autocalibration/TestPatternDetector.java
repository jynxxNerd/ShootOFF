package com.shootoff.camera.autocalibration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Optional;

import javax.imageio.ImageIO;

import org.bytedeco.javacpp.Loader;
import org.bytedeco.opencv.opencv_java;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.shootoff.calibration.CalibrationCheck;
import com.shootoff.camera.MockCamera;
import com.shootoff.geom.Rect;

class TestPatternDetector {
	@BeforeAll
	static void loadOpenCv() {
		Loader.load(opencv_java.class);
	}

	private static PatternDetector detector() {
		final MockCamera camera = new MockCamera();
		camera.setViewSize(new Dimension(640, 480));
		return new PatternDetector(camera);
	}

	// A 640x480 camera frame of a dim wall with the projected pattern at <tt>where</tt>
	private static BufferedImage frame(Rect where) throws IOException {
		final BufferedImage frame = new BufferedImage(640, 480, BufferedImage.TYPE_3BYTE_BGR);
		final Graphics2D g = frame.createGraphics();
		g.setColor(new Color(40, 40, 40));
		g.fillRect(0, 0, 640, 480);
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.drawImage(ImageIO.read(TestPatternDetector.class.getResource("/pattern.png")), (int) where.getMinX(),
				(int) where.getMinY(), (int) where.getWidth(), (int) where.getHeight(), null);
		g.dispose();
		return frame;
	}

	@Test
	void theProjectedPatternIsFoundWhereItIs() throws IOException {
		final Rect projected = new Rect(100, 80, 420, 296);

		final Optional<Rect> found = detector().detect(frame(projected));

		assertTrue(found.isPresent());
		assertTrue(CalibrationCheck.distance(projected, found.get()) <= CalibrationCheck.DEFAULT_TOLERANCE,
				() -> "found " + found.get());
	}

	@Test
	void aFrameWithoutThePatternFindsNothing() throws IOException {
		final BufferedImage wall = new BufferedImage(640, 480, BufferedImage.TYPE_3BYTE_BGR);

		assertEquals(Optional.empty(), detector().detect(wall));
	}
}
