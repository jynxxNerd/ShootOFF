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

package com.shootoff.camera.recorders;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.shootoff.Closeable;
import com.shootoff.camera.video.BgrImages;
import com.shootoff.camera.video.TimedFrame;
import com.shootoff.camera.video.VideoReader;
import com.shootoff.camera.video.VideoWriter;

public class RollingRecorder implements Closeable {
	private final Logger logger = LoggerFactory.getLogger(RollingRecorder.class);

	private final String extension;
	private final String sessionName;
	private final String cameraName;
	private final int recordWidth;
	private final int recordHeight;
	private final LongSupplier clock;

	// Serializes fork(boolean) and close() against each other: a rolling fork
	// (tryLock, skipped if a fork is already running) and a shot fork/close
	// (lock, waits for any in-flight fork) never run at the same time.
	private final ReentrantLock forkLock = new ReentrantLock();

	// Guards every field below, including bufferedFrames' contents.
	private final Object stateLock = new Object();

	private long startTime;
	private long timeOffset = 0;
	private File relativeVideoFile;
	private File videoFile;
	private VideoWriter videoWriter;
	private boolean forking = false;
	private boolean recording = true;

	// Frames that arrive while the video is being forked, stamped with the
	// clock time they arrived at
	private final List<TimedFrame> bufferedFrames = new ArrayList<>();

	public RollingRecorder(String extension, String sessionName, String cameraName, int recordWidth,
			int recordHeight) throws IOException {
		this(extension, sessionName, cameraName, recordWidth, recordHeight, System::currentTimeMillis);
	}

	RollingRecorder(String extension, String sessionName, String cameraName, int recordWidth, int recordHeight,
			LongSupplier clock) throws IOException {
		this.extension = extension;
		this.sessionName = sessionName;
		this.cameraName = cameraName;
		this.recordWidth = recordWidth;
		this.recordHeight = recordHeight;
		this.clock = clock;

		startTime = clock.getAsLong();
		relativeVideoFile = newRelativeVideoFile(true);
		videoFile = toSessionsFile(relativeVideoFile);
		videoWriter = new VideoWriter(videoFile, recordWidth, recordHeight);

		logger.debug("Started recording new rolling video: {}", videoFile.getName());
	}

	private File newRelativeVideoFile(boolean rolling) {
		return new File(sessionName + File.separator + (rolling ? "rolling" : "") + System.nanoTime() + extension);
	}

	private static File toSessionsFile(File relativeVideoFile) {
		return new File(System.getProperty("shootoff.sessions") + File.separator + relativeVideoFile.getPath());
	}

	public void recordFrame(BufferedImage frame) {
		final long now = clock.getAsLong();
		boolean needsRoll = false;

		synchronized (stateLock) {
			if (!recording) return;

			if (forking) {
				// A fork is cutting the current video; buffer this frame so it
				// can be rebased onto the new video's timeline once the fork
				// installs its writer.
				bufferedFrames.add(new TimedFrame(BgrImages.copy(frame), now * 1000));
				return;
			}

			final long timestamp = (now - startTime) + timeOffset;
			write(videoWriter, frame, timestamp);

			if (timestamp >= ShotRecorder.RECORD_LENGTH * 3) {
				logger.debug("Rolling video file {}, timestamp = {} ms", relativeVideoFile.getPath(), timestamp);
				needsRoll = true;
			}
		}

		if (!needsRoll) return;

		// A shot fork (or another roll) may already be running; skip this
		// roll rather than block the camera thread, the next frame re-checks.
		if (forkLock.tryLock()) {
			try {
				fork(false);
			} catch (final IOException | RuntimeException e) {
				// fork() already cleaned up its own state (recording/forking)
				// and, for a real failure, already logged it once before
				// rethrowing; catching it here just keeps that failure from
				// propagating out of recordFrame and killing the camera
				// thread. Do not catch Error here: something like an OOM
				// should still surface.
			} finally {
				forkLock.unlock();
			}
		}
	}

	private void write(VideoWriter writer, BufferedImage frame, long timestamp) {
		try {
			writer.write(frame, timestamp);
		} catch (final IOException e) {
			logger.error("Failed to record frame to {}", videoFile.getPath(), e);
		}
	}

	/**
	 * Cut the last RECORD_LENGTH of the current video into a new file. Must be
	 * called while holding forkLock.
	 *
	 * @param keepOld
	 *            true when forking for a shot: the cut becomes the shot video
	 *            and rolling continues in a copy of the current video. false
	 *            when rolling: the cut becomes the new rolling video.
	 */
	private ForkContext fork(boolean keepOld) throws IOException {
		final File currentVideoFile;
		final File currentRelativeVideoFile;

		synchronized (stateLock) {
			if (!recording) throw new IOException("Recording has already stopped");

			forking = true;
			videoWriter.close();
			currentVideoFile = videoFile;
			currentRelativeVideoFile = relativeVideoFile;
		}

		// The slow cut/copy work below runs with no lock held so the camera
		// thread can keep buffering frames instead of blocking.
		VideoWriter cutWriter = null;
		File cutVideoFile = null;
		VideoWriter copyWriter = null;
		File copyVideoFile = null;

		try {
			final File forkRelativeVideoFile = newRelativeVideoFile(!keepOld);
			final File forkVideoFile = toSessionsFile(forkRelativeVideoFile);
			cutVideoFile = forkVideoFile;

			final Cut cut;
			try (VideoReader reader = new VideoReader(currentVideoFile)) {
				final long startCutTimestamp = reader.getDurationMs() - ShotRecorder.RECORD_LENGTH;

				logger.debug("Forking video file {} to {}, keepOld = {}, start cutting at = {} ms",
						currentRelativeVideoFile.getPath(), forkRelativeVideoFile.getPath(), keepOld,
						startCutTimestamp);

				cut = cut(reader, forkVideoFile, startCutTimestamp);
			}
			cutWriter = cut.writer;

			final ForkContext context = new ForkContext(forkRelativeVideoFile, forkVideoFile, cut.lastTimestamp,
					cut.writer);

			final File nextRelativeVideoFile;
			final File nextVideoFile;
			final long nextTimeOffset;
			final VideoWriter nextWriter;

			if (keepOld) {
				final File rollingRelativeVideoFile = newRelativeVideoFile(true);
				final File rollingVideoFile = toSessionsFile(rollingRelativeVideoFile);
				copyVideoFile = rollingVideoFile;

				final Cut copy;
				try (VideoReader reader = new VideoReader(currentVideoFile)) {
					copy = cut(reader, rollingVideoFile, -1);
				}
				copyWriter = copy.writer;

				nextRelativeVideoFile = rollingRelativeVideoFile;
				nextVideoFile = rollingVideoFile;
				nextTimeOffset = copy.lastTimestamp;
				nextWriter = copy.writer;
			} else {
				nextRelativeVideoFile = forkRelativeVideoFile;
				nextVideoFile = forkVideoFile;
				nextTimeOffset = cut.lastTimestamp;
				nextWriter = cut.writer;
			}

			if (!currentVideoFile.delete()) {
				logger.warn("Failed to delete expired rolling video file: {}", currentVideoFile.getPath());
			}

			synchronized (stateLock) {
				// Frames that arrived during the fork continue the new video's
				// timeline. Compute everything that can still fail (the clock
				// read) before mutating any field, so a failure here leaves
				// this instance exactly as it was before this fork attempt;
				// the fields are only committed once nothing more can throw.
				final long nextStartTime = bufferedFrames.isEmpty() ? clock.getAsLong()
						: bufferedFrames.get(0).getTimestampMs();

				for (final TimedFrame f : bufferedFrames)
					write(nextWriter, f.getImage(), (f.getTimestampMs() - nextStartTime) + nextTimeOffset);

				bufferedFrames.clear();

				relativeVideoFile = nextRelativeVideoFile;
				videoFile = nextVideoFile;
				timeOffset = nextTimeOffset;
				startTime = nextStartTime;
				videoWriter = nextWriter;
				forking = false;
			}

			return context;
		} catch (final IOException | RuntimeException | Error t) {
			// Run the same cleanup for any failure in the unlocked section
			// above (including a RuntimeException/Error, e.g. an OOM during
			// the cut), not just IOException: otherwise forking would stay
			// true forever, cutWriter/copyWriter would leak, and recordFrame
			// would buffer frames without limit.
			if (cutWriter != null) cutWriter.close();
			if (copyWriter != null) copyWriter.close();

			if (cutVideoFile != null && !cutVideoFile.delete())
				logger.warn("Failed to delete partial fork video file: {}", cutVideoFile.getPath());
			if (copyVideoFile != null && !copyVideoFile.delete())
				logger.warn("Failed to delete partial fork video file: {}", copyVideoFile.getPath());

			synchronized (stateLock) {
				bufferedFrames.clear();
				recording = false;
				forking = false;
			}

			logger.error("Failed to fork video file {}; recording stopped", currentVideoFile.getPath(), t);

			throw t;
		}
	}

	/**
	 * Copy the frames at or after startingTimestamp into a new video whose
	 * timestamps start at 0. A negative startingTimestamp copies every frame
	 * (the source has less than RECORD_LENGTH of footage). The returned writer
	 * is left open.
	 */
	private Cut cut(VideoReader reader, File newVideoFile, long startingTimestamp /* ms */) throws IOException {
		final VideoWriter writer = new VideoWriter(newVideoFile, recordWidth, recordHeight);

		try {
			long firstTimestamp = -1;
			long lastTimestamp = 0;

			Optional<TimedFrame> frame;
			while ((frame = reader.next()).isPresent()) {
				final long timestamp = frame.get().getTimestampMs();

				if (startingTimestamp >= 0 && timestamp < startingTimestamp) continue;

				if (firstTimestamp == -1) firstTimestamp = timestamp;

				lastTimestamp = timestamp - firstTimestamp;
				writer.write(frame.get().getImage(), lastTimestamp);
			}

			return new Cut(writer, lastTimestamp);
		} catch (final IOException e) {
			writer.close();
			throw e;
		}
	}

	public Optional<ShotRecorder> fork() {
		forkLock.lock();

		try {
			synchronized (stateLock) {
				if (!recording) return Optional.empty();
			}

			final ForkContext context = fork(true);
			return Optional.of(new ShotRecorder(context.relativeVideoFile, context.videoFile, context.lastTimestamp,
					context.videoWriter, cameraName, clock));
		} catch (final IOException e) {
			// Either fork() logged a real failure and stopped recording, or
			// recording had already stopped for some other reason (nothing
			// new to log here either way).
			return Optional.empty();
		} finally {
			forkLock.unlock();
		}
	}

	private static class Cut {
		private final VideoWriter writer;
		private final long lastTimestamp;

		private Cut(VideoWriter writer, long lastTimestamp) {
			this.writer = writer;
			this.lastTimestamp = lastTimestamp;
		}
	}

	private static class ForkContext {
		private final File relativeVideoFile;
		private final File videoFile;
		private final long lastTimestamp;
		private final VideoWriter videoWriter;

		private ForkContext(File relativeVideoFile, File videoFile, long lastTimestamp, VideoWriter videoWriter) {
			this.relativeVideoFile = relativeVideoFile;
			this.videoFile = videoFile;
			this.lastTimestamp = lastTimestamp;
			this.videoWriter = videoWriter;
		}
	}

	@Override
	public void close() {
		// Wait for any in-flight fork (roll or shot) to finish so we never
		// close/delete a video file a fork is still reading or writing.
		forkLock.lock();

		try {
			synchronized (stateLock) {
				recording = false;
				videoWriter.close();

				if (!videoFile.delete()) {
					logger.warn("Failed to delete expired rolling video file on close: {}", videoFile.getPath());
				}

				bufferedFrames.clear();
			}
		} finally {
			forkLock.unlock();
		}
	}
}
