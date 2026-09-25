package com.shootoff.camera.video;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class TestVideoIO {
	@Rule public TemporaryFolder folder = new TemporaryFolder();

	private static BufferedImage solid(int width, int height, Color color) {
		final BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
		final Graphics2D g = image.createGraphics();
		g.setColor(color);
		g.fillRect(0, 0, width, height);
		g.dispose();
		return image;
	}

	private static List<TimedFrame> readAll(File file) throws IOException {
		final List<TimedFrame> frames = new ArrayList<>();
		try (VideoReader reader = new VideoReader(file)) {
			Optional<TimedFrame> frame;
			while ((frame = reader.next()).isPresent())
				frames.add(frame.get());
		}
		return frames;
	}

	@Test
	public void testRoundTripKeepsFrameCountSizeTimingAndColor() throws IOException {
		final File file = new File(folder.getRoot(), "roundtrip.mp4");

		try (VideoWriter writer = new VideoWriter(file, 640, 480)) {
			for (int i = 0; i < 30; i++)
				writer.write(solid(640, 480, Color.RED), i * 33);
		}

		final List<TimedFrame> frames = readAll(file);
		assertEquals(30, frames.size(), 1);

		long last = -1;
		for (final TimedFrame frame : frames) {
			assertEquals(BufferedImage.TYPE_3BYTE_BGR, frame.getImage().getType());
			assertEquals(640, frame.getImage().getWidth());
			assertEquals(480, frame.getImage().getHeight());
			assertTrue(frame.getTimestampMs() > last);
			last = frame.getTimestampMs();
		}
		assertEquals(29 * 33, last, 34);

		final Color center = new Color(frames.get(0).getImage().getRGB(320, 240));
		assertTrue("red channel " + center.getRed(), center.getRed() > 200);
		assertTrue("blue channel " + center.getBlue(), center.getBlue() < 60);

		try (VideoReader reader = new VideoReader(file)) {
			assertEquals(29 * 33, reader.getDurationMs(), 100);
		}
	}

	@Test
	public void testNonIncreasingTimestampsAreAccepted() throws IOException {
		final File file = new File(folder.getRoot(), "jitter.mp4");

		try (VideoWriter writer = new VideoWriter(file, 64, 48)) {
			for (final long timestamp : new long[] { 0, 0, 10, 5 })
				writer.write(solid(64, 48, Color.GREEN), timestamp);
		}

		final List<TimedFrame> frames = readAll(file);
		assertEquals(4, frames.size());
		for (int i = 1; i < frames.size(); i++)
			assertTrue(frames.get(i).getTimestampMs() > frames.get(i - 1).getTimestampMs());
	}

	@Test
	public void testSubimageWithOddSizeIsRecorded() throws IOException {
		final File file = new File(folder.getRoot(), "cropped.mp4");
		final BufferedImage parent = solid(640, 480, Color.BLACK);
		final Graphics2D g = parent.createGraphics();
		g.setColor(Color.WHITE);
		g.fillRect(100, 100, 101, 75);
		g.dispose();

		try (VideoWriter writer = new VideoWriter(file, 101, 75)) {
			writer.write(parent.getSubimage(100, 100, 101, 75), 0);
		}

		final List<TimedFrame> frames = readAll(file);
		assertEquals(1, frames.size());
		assertEquals(102, frames.get(0).getImage().getWidth());
		assertEquals(76, frames.get(0).getImage().getHeight());
		// The cropped region is white; recording the parent's origin would be black
		assertTrue(new Color(frames.get(0).getImage().getRGB(50, 37)).getGreen() > 200);
	}

	@Test
	public void testRewindStartsFromTheFirstFrame() throws IOException {
		final File file = new File(folder.getRoot(), "rewind.mp4");
		try (VideoWriter writer = new VideoWriter(file, 64, 48)) {
			for (int i = 0; i < 10; i++)
				writer.write(solid(64, 48, Color.BLUE), i * 33);
		}

		try (VideoReader reader = new VideoReader(file)) {
			while (reader.next().isPresent()) {}
			reader.rewind();
			assertTrue(reader.next().get().getTimestampMs() < 50);
		}
	}

	@Test(expected = FileNotFoundException.class)
	public void testMissingVideoIsReported() throws IOException {
		new VideoReader(new File(folder.getRoot(), "missing.mp4")).close();
	}

	@Test
	public void testToBgrOnlyCopiesWhenNeeded() {
		final BufferedImage bgr = solid(10, 10, Color.RED);
		assertSame(bgr, BgrImages.toBgr(bgr));

		final BufferedImage sub = bgr.getSubimage(2, 2, 4, 4);
		final BufferedImage subCopy = BgrImages.toBgr(sub);
		assertNotSame(sub, subCopy);
		assertEquals(sub.getRGB(1, 1), subCopy.getRGB(1, 1));

		final BufferedImage rgb = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
		rgb.setRGB(3, 4, 0x123456);
		final BufferedImage converted = BgrImages.toBgr(rgb);
		assertEquals(BufferedImage.TYPE_3BYTE_BGR, converted.getType());
		assertEquals(0x123456, converted.getRGB(3, 4) & 0xFFFFFF);
	}
}
