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
import java.util.function.LongSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.shootoff.Closeable;
import com.shootoff.camera.video.VideoWriter;

public class ShotRecorder implements Closeable {
	// The number of milliseconds before and after a shot to record
	public static final long RECORD_LENGTH = 5000; // ms

	private static final Logger logger = LoggerFactory.getLogger(ShotRecorder.class);

	private final LongSupplier clock;
	private final long startTime;
	private final long timeOffset;
	private final File relativeVideoFile;
	private final File videoFile;
	private final String cameraName;
	private final VideoWriter videoWriter;

	public ShotRecorder(File relativeVideoFile, File videoFile, long cutDuration, VideoWriter videoWriter,
			String cameraName, LongSupplier clock) {
		this.relativeVideoFile = relativeVideoFile;
		this.videoFile = videoFile;
		this.videoWriter = videoWriter;
		this.cameraName = cameraName;
		this.clock = clock;

		startTime = clock.getAsLong();
		timeOffset = cutDuration;

		logger.debug("Started recording shot video: {}, cut duration = {} ms", videoFile.getName(), cutDuration);
	}

	public void recordFrame(BufferedImage frame) {
		final long timestamp = (clock.getAsLong() - startTime) + timeOffset;

		try {
			videoWriter.write(frame, timestamp);
		} catch (final IOException e) {
			logger.error("Failed to record shot video frame to {}", videoFile.getPath(), e);
		}
	}

	public File getRelativeVideoFile() {
		return relativeVideoFile;
	}

	public File getVideoFile() {
		return videoFile;
	}

	public String getCameraName() {
		return cameraName;
	}

	public boolean isComplete() {
		return clock.getAsLong() - startTime > RECORD_LENGTH;
	}

	@Override
	public void close() {
		videoWriter.close();

		logger.debug("Stopped recording shot video: {}, timeOffset = {}", relativeVideoFile.getPath(), timeOffset);
	}
}
