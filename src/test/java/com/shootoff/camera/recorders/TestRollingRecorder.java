package com.shootoff.camera.recorders;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

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

	@Test(timeout = 60_000)
	public void testConcurrentForkWhileRecording() throws Exception {
		final RollingRecorder rolling = newRecorder();

		final AtomicBoolean stop = new AtomicBoolean(false);
		final AtomicInteger written = new AtomicInteger(0);
		final AtomicReference<Optional<ShotRecorder>> shotRef = new AtomicReference<>(Optional.empty());
		final AtomicReference<Throwable> recordingError = new AtomicReference<>();
		final CountDownLatch someFramesWritten = new CountDownLatch(1);

		// Records continuously on its own thread, advancing the fake clock
		// itself, while fork() below runs concurrently on the test thread.
		// A short real sleep paces frame arrival so a slow fork() can't let
		// this thread buffer an unbounded number of frames in memory.
		final Thread recordingThread = new Thread(() -> {
			try {
				int i = 0;
				while (!stop.get()) {
					final BufferedImage image = frame(i++);
					rolling.recordFrame(image);
					shotRef.get().ifPresent(shot -> shot.recordFrame(image));
					now.addAndGet(FRAME_MS);

					if (written.incrementAndGet() == 20) someFramesWritten.countDown();

					Thread.sleep(1);
				}
			} catch (final Throwable t) {
				recordingError.set(t);
			}
		}, "ConcurrentRecording");
		recordingThread.start();

		assertTrue("recording thread should make progress", someFramesWritten.await(10, TimeUnit.SECONDS));

		final AtomicReference<Throwable> forkError = new AtomicReference<>();
		Optional<ShotRecorder> shot = Optional.empty();
		try {
			shot = rolling.fork();
			shotRef.set(shot);
		} catch (final Throwable t) {
			forkError.set(t);
		}

		// Keep recording a bit longer so the shot video also gets live
		// frames written to it after the fork, same as CameraManager does.
		final int targetAfterFork = written.get() + 30;
		while (written.get() < targetAfterFork && recordingThread.isAlive())
			Thread.yield();

		stop.set(true);
		recordingThread.join(10_000);
		assertFalse("recording thread did not finish", recordingThread.isAlive());

		assertNull("fork() threw on the test thread", forkError.get());
		assertNull("recordFrame() threw on the recording thread", recordingError.get());
		assertTrue("fork() should have produced a shot recorder", shot.isPresent());

		shot.get().close();
		rolling.close();

		final long[] timestamps = frameTimestamps(shot.get().getVideoFile());
		assertTrue("shot video has no frames", timestamps.length > 0);
		assertIncreasing(timestamps);

		// No frame arriving during the fork should have been lost, which
		// would otherwise show up as a multi-second gap in the timeline.
		for (int i = 1; i < timestamps.length; i++) {
			final long delta = timestamps[i] - timestamps[i - 1];
			assertTrue("gap of " + delta + " ms in the rolling timeline at frame " + i, delta <= 1000);
		}

		// Only the shot video remains; rolling files are deleted
		final String[] remaining = sessionFolder.list();
		assertEquals(Arrays.toString(remaining), 1, remaining.length);
		assertEquals(shot.get().getVideoFile().getName(), remaining[0]);
	}

	@Test(timeout = 30_000)
	public void testCloseWhileForkingLeavesNoFilesBehind() throws Exception {
		final RollingRecorder rolling = newRecorder();
		record(rolling, Optional.empty(), 500); // 16.5 s, gives fork() real cutting work to do

		final AtomicReference<Throwable> forkError = new AtomicReference<>();
		final AtomicReference<Optional<ShotRecorder>> shotRef = new AtomicReference<>(Optional.empty());
		final CountDownLatch forkDone = new CountDownLatch(1);

		final Thread forkThread = new Thread(() -> {
			try {
				shotRef.set(rolling.fork());
			} catch (final Throwable t) {
				forkError.set(t);
			} finally {
				forkDone.countDown();
			}
		}, "ConcurrentFork");
		forkThread.start();

		// Race close() against the in-flight fork; close() must wait for it
		// (forkLock) instead of closing/deleting files out from under it.
		final AtomicReference<Throwable> closeError = new AtomicReference<>();
		try {
			rolling.close();
		} catch (final Throwable t) {
			closeError.set(t);
		}

		assertTrue("fork thread did not finish", forkDone.await(10, TimeUnit.SECONDS));
		forkThread.join(10_000);

		assertNull("close() threw", closeError.get());
		assertNull("fork() threw", forkError.get());

		// Whichever thread reached forkLock first, no rolling files may
		// remain: either the fork completed first and close() cleaned up
		// its rolling continuation file, or close() stopped recording first
		// and the fork found nothing to do.
		final String[] remaining = sessionFolder.list();
		if (shotRef.get().isPresent()) {
			final ShotRecorder shot = shotRef.get().get();
			shot.close();
			assertEquals(Arrays.toString(remaining), 1, remaining.length);
			assertEquals(shot.getVideoFile().getName(), remaining[0]);
		} else {
			assertEquals(Arrays.toString(remaining), 0, remaining.length);
		}
	}

	@Test(timeout = 30_000)
	public void testForkCleansUpOnRuntimeExceptionDuringTheUnlockedPhase() throws IOException {
		// The clock is the one seam fork() calls into after the real cut/copy
		// work below has already created open writers and real files:
		// throwing from it exercises the same cleanup path a converter
		// failure or an OOM mid-cut would, without mocking VideoWriter/Reader.
		final AtomicBoolean clockShouldThrow = new AtomicBoolean(false);
		final LongSupplier throwingClock = () -> {
			if (clockShouldThrow.get()) throw new RuntimeException("Simulated clock failure");
			return now.get();
		};

		final RollingRecorder rolling = new RollingRecorder(".mp4", "test", "cam", WIDTH, HEIGHT, throwingClock);
		record(rolling, Optional.empty(), 200); // 6.6 s, gives fork() real cutting work to do

		clockShouldThrow.set(true);

		try {
			rolling.fork();
			fail("fork() should have propagated the RuntimeException instead of swallowing it");
		} catch (final RuntimeException e) {
			assertEquals("Simulated clock failure", e.getMessage());
		}

		// The original rolling file is already gone by this point (fork()
		// deletes it once the cut/copy succeeds), and the cut/copy writers
		// and files created for this failed fork attempt must not leak
		// either: nothing is left on disk.
		final String[] remaining = sessionFolder.list();
		assertEquals(Arrays.toString(remaining), 0, remaining.length);

		// forking/recording must both be cleared, not left stuck on forever:
		// a frame recorded now (with a normal, non-throwing clock call) must
		// be dropped immediately (recording stopped), not appended to an
		// ever-growing in-memory buffer.
		clockShouldThrow.set(false);
		rolling.recordFrame(frame(999));
	}
}
