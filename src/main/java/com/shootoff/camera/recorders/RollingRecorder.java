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

	private long startTime;
	private long timeOffset = 0;
	private File relativeVideoFile;
	private File videoFile;
	private VideoWriter videoWriter;
	private final Object videoWriterLock = new Object();
	private volatile boolean forking = false;
	private volatile boolean recording = true;

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
		if (!recording) return;

		final long now = clock.getAsLong();

		if (forking) {
			synchronized (bufferedFrames) {
				bufferedFrames.add(new TimedFrame(BgrImages.copy(frame), now * 1000));
			}
			return;
		}

		final long timestamp = (now - startTime) + timeOffset;

		synchronized (videoWriterLock) {
			write(videoWriter, frame, timestamp);
		}

		if (timestamp >= ShotRecorder.RECORD_LENGTH * 3) {
			logger.debug("Rolling video file {}, timestamp = {} ms", relativeVideoFile.getPath(), timestamp);

			try {
				fork(false);
			} catch (final IOException e) {
				logger.error("Failed to roll video file {}; recording stopped", videoFile.getPath(), e);
				recording = false;
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
	 * Cut the last RECORD_LENGTH of the current video into a new file.
	 *
	 * @param keepOld
	 *            true when forking for a shot: the cut becomes the shot video
	 *            and rolling continues in a copy of the current video. false
	 *            when rolling: the cut becomes the new rolling video.
	 */
	private ForkContext fork(boolean keepOld) throws IOException {
		forking = true;

		try {
			synchronized (videoWriterLock) {
				videoWriter.close();
			}

			final File forkRelativeVideoFile = newRelativeVideoFile(!keepOld);
			final File forkVideoFile = toSessionsFile(forkRelativeVideoFile);

			final Cut cut;
			try (VideoReader reader = new VideoReader(videoFile)) {
				final long startCutTimestamp = reader.getDurationMs() - ShotRecorder.RECORD_LENGTH;

				logger.debug("Forking video file {} to {}, keepOld = {}, start cutting at = {} ms",
						relativeVideoFile.getPath(), forkRelativeVideoFile.getPath(), keepOld, startCutTimestamp);

				cut = cut(reader, forkVideoFile, startCutTimestamp);
			}

			final ForkContext context = new ForkContext(forkRelativeVideoFile, forkVideoFile, cut.lastTimestamp,
					cut.writer);

			final VideoWriter nextWriter;
			if (keepOld) {
				final File rollingRelativeVideoFile = newRelativeVideoFile(true);
				final File rollingVideoFile = toSessionsFile(rollingRelativeVideoFile);

				final Cut copy;
				try (VideoReader reader = new VideoReader(videoFile)) {
					copy = cut(reader, rollingVideoFile, -1);
				}

				deleteExpiredVideo();
				relativeVideoFile = rollingRelativeVideoFile;
				videoFile = rollingVideoFile;
				timeOffset = copy.lastTimestamp;
				nextWriter = copy.writer;
			} else {
				deleteExpiredVideo();
				relativeVideoFile = forkRelativeVideoFile;
				videoFile = forkVideoFile;
				timeOffset = cut.lastTimestamp;
				nextWriter = cut.writer;
			}

			synchronized (bufferedFrames) {
				// Frames that arrived during the fork continue the new video's timeline
				startTime = bufferedFrames.isEmpty() ? clock.getAsLong() : bufferedFrames.get(0).getTimestampMs();

				for (final TimedFrame f : bufferedFrames)
					write(nextWriter, f.getImage(), (f.getTimestampMs() - startTime) + timeOffset);

				bufferedFrames.clear();
			}

			synchronized (videoWriterLock) {
				videoWriter = nextWriter;
			}

			return context;
		} finally {
			forking = false;
		}
	}

	private void deleteExpiredVideo() {
		if (!videoFile.delete()) logger.warn("Failed to delete expired rolling video file: {}", videoFile.getPath());
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
		if (!recording) return Optional.empty();

		try {
			final ForkContext context = fork(true);
			return Optional.of(new ShotRecorder(context.relativeVideoFile, context.videoFile, context.lastTimestamp,
					context.videoWriter, cameraName, clock));
		} catch (final IOException e) {
			logger.error("Failed to fork video file {} for a shot; recording stopped", videoFile.getPath(), e);
			recording = false;
			return Optional.empty();
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
		recording = false;

		synchronized (videoWriterLock) {
			videoWriter.close();
		}

		if (!videoFile.delete()) {
			logger.warn("Failed to delete expired rolling video file on close: {}", videoFile.getPath());
		}
	}
}
