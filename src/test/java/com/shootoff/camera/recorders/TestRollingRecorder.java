package com.shootoff.camera.recorders;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.shootoff.camera.video.TimedFrame;
import com.shootoff.camera.video.VideoReader;

public class TestRollingRecorder {
	@Rule public TemporaryFolder folder = new TemporaryFolder();

	private static final int WIDTH = 320;
	private static final int HEIGHT = 240;
	private static final long FRAME_MS = 33;

	private final AtomicLong now = new AtomicLong(1_000_000);
	private File sessionFolder;

	@Before
	public void setUp() {
		System.setProperty("shootoff.sessions", folder.getRoot().getAbsolutePath());
		sessionFolder = new File(folder.getRoot(), "test");
		assertTrue(sessionFolder.mkdirs());
	}

	private static BufferedImage frame(int i) {
		final BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_3BYTE_BGR);
		final Graphics2D g = image.createGraphics();
		g.setColor(new Color((i * 7) % 256, 64, 128));
		g.fillRect(0, 0, WIDTH, HEIGHT);
		g.dispose();
		return image;
	}

	private RollingRecorder newRecorder() throws IOException {
		return new RollingRecorder(".mp4", "test", "cam", WIDTH, HEIGHT, now::get);
	}

	private void record(RollingRecorder rolling, Optional<ShotRecorder> shot, int frames) {
		for (int i = 0; i < frames; i++) {
			rolling.recordFrame(frame(i));
			if (shot.isPresent()) shot.get().recordFrame(frame(i));
			now.addAndGet(FRAME_MS);
		}
	}

	private static long[] frameTimestamps(File video) throws IOException {
		try (VideoReader reader = new VideoReader(video)) {
			final long[] timestamps = new long[10_000];
			int count = 0;
			Optional<TimedFrame> frame;
			while ((frame = reader.next()).isPresent())
				timestamps[count++] = frame.get().getTimestampMs();
			return Arrays.copyOf(timestamps, count);
		}
	}

	private static void assertIncreasing(long[] timestamps) {
		for (int i = 1; i < timestamps.length; i++)
			assertTrue("timestamps must increase at " + i, timestamps[i] > timestamps[i - 1]);
	}

	@Test
	public void testShotVideoHasRecordLengthBeforeShotPlusFollowingFrames() throws IOException {
		final RollingRecorder rolling = newRecorder();
		record(rolling, Optional.empty(), 200); // 6.6 s

		final Optional<ShotRecorder> shot = rolling.fork();
		assertTrue(shot.isPresent());
		record(rolling, shot, 30);
		shot.get().close();
		rolling.close();

		final long[] timestamps = frameTimestamps(shot.get().getVideoFile());
		assertIncreasing(timestamps);
		assertEquals(ShotRecorder.RECORD_LENGTH / FRAME_MS + 30, timestamps.length, 3);
		assertEquals(ShotRecorder.RECORD_LENGTH + 30 * FRAME_MS, timestamps[timestamps.length - 1], 150);
	}

	@Test
	public void testShotRightAfterStartKeepsAllFrames() throws IOException {
		final RollingRecorder rolling = newRecorder();
		record(rolling, Optional.empty(), 10);

		final Optional<ShotRecorder> shot = rolling.fork();
		record(rolling, shot, 5);
		shot.get().close();
		rolling.close();

		final long[] timestamps = frameTimestamps(shot.get().getVideoFile());
		assertIncreasing(timestamps);
		assertEquals(15, timestamps.length, 1);
	}

	@Test
	public void testRolledRecordingStillProducesShotVideoAndCleansUp() throws IOException {
		final RollingRecorder rolling = newRecorder();
		record(rolling, Optional.empty(), 500); // 16.5 s, rolls once at 15 s

		final Optional<ShotRecorder> shot = rolling.fork();
		record(rolling, shot, 30);
		shot.get().close();
		rolling.close();

		final long[] timestamps = frameTimestamps(shot.get().getVideoFile());
		assertIncreasing(timestamps);
		assertEquals(ShotRecorder.RECORD_LENGTH / FRAME_MS + 30, timestamps.length, 3);

		// Only the shot video remains; rolling files are deleted
		final String[] remaining = sessionFolder.list();
		assertEquals(Arrays.toString(remaining), 1, remaining.length);
		assertEquals(shot.get().getVideoFile().getName(), remaining[0]);
	}
}
