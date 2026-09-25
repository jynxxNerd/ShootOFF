package com.shootoff.camera.video;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Optional;

import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads the video frames of a file in order as TYPE_3BYTE_BGR images.
 */
public final class VideoReader implements AutoCloseable {
	private static final Logger logger = LoggerFactory.getLogger(VideoReader.class);

	private final File file;
	private final FFmpegFrameGrabber grabber;
	private final Java2DFrameConverter converter = new Java2DFrameConverter();

	public VideoReader(File file) throws IOException {
		if (!file.isFile()) throw new FileNotFoundException(file.getPath());

		this.file = file;
		grabber = new FFmpegFrameGrabber(file);
		grabber.setPixelFormat(avutil.AV_PIX_FMT_BGR24);

		try {
			grabber.start();
		} catch (final FrameGrabber.Exception e) {
			throw new IOException("Could not open video " + file.getPath(), e);
		}
	}

	public long getDurationMs() {
		return grabber.getLengthInTime() / 1000;
	}

	/**
	 * @return the next frame, or empty at the end of the video. The image is
	 *         never reused by later calls.
	 */
	public synchronized Optional<TimedFrame> next() throws IOException {
		try {
			final Frame frame = grabber.grabImage();

			if (frame == null) return Optional.empty();

			return Optional.of(new TimedFrame(Java2DFrameConverter.cloneBufferedImage(converter.convert(frame)),
					frame.timestamp));
		} catch (final FrameGrabber.Exception e) {
			throw new IOException("Could not read frame from " + file.getPath(), e);
		}
	}

	public synchronized void rewind() throws IOException {
		try {
			grabber.setTimestamp(0);
		} catch (final FrameGrabber.Exception e) {
			throw new IOException("Could not rewind " + file.getPath(), e);
		}
	}

	@Override
	public synchronized void close() {
		try {
			grabber.stop();
			grabber.release();
		} catch (final FrameGrabber.Exception e) {
			logger.error("Error closing video {}", file.getPath(), e);
		}
	}
}
