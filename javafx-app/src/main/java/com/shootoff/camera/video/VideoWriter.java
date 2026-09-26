package com.shootoff.camera.video;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.FrameRecorder;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes frames with millisecond timestamps to an MPEG-4 video.
 */
public final class VideoWriter implements AutoCloseable {
	private static final Logger logger = LoggerFactory.getLogger(VideoWriter.class);

	// Frames arrive at a variable rate with millisecond timestamps, so use a
	// millisecond time base and let each frame's timestamp set its position
	private static final int TIME_BASE = 1000;

	private final File file;
	private final FFmpegFrameRecorder recorder;
	private final Java2DFrameConverter converter = new Java2DFrameConverter();
	private long lastTimestamp = -1;
	private boolean open;

	public VideoWriter(File file, int width, int height) throws IOException {
		this.file = file;

		// The YUV 4:2:0 encoder needs even dimensions; frames are scaled to fit
		recorder = new FFmpegFrameRecorder(file, width + (width & 1), height + (height & 1), 0);
		recorder.setFormat("mp4");
		recorder.setVideoCodec(avcodec.AV_CODEC_ID_MPEG4);
		recorder.setPixelFormat(avutil.AV_PIX_FMT_YUV420P);
		recorder.setFrameRate(TIME_BASE);
		recorder.setGopSize(30);
		recorder.setMaxBFrames(0);
		recorder.setVideoQuality(2);

		try {
			recorder.start();
		} catch (final FrameRecorder.Exception e) {
			throw new IOException("Could not start recording " + file.getPath(), e);
		}

		open = true;
	}

	/**
	 * Timestamps must increase; a timestamp at or before the previous one is
	 * recorded 1 ms after the previous frame instead.
	 */
	public synchronized void write(BufferedImage image, long timestampMs) throws IOException {
		if (!open) throw new IOException("Video writer is closed: " + file.getPath());

		final long timestamp = Math.max(timestampMs, lastTimestamp + 1);

		try {
			recorder.setTimestamp(timestamp * 1000);
			recorder.record(converter.convert(BgrImages.toBgr(image)));
		} catch (final FrameRecorder.Exception e) {
			throw new IOException("Could not record frame to " + file.getPath(), e);
		}

		lastTimestamp = timestamp;
	}

	public synchronized boolean isOpen() {
		return open;
	}

	@Override
	public synchronized void close() {
		if (!open) return;
		open = false;

		try {
			recorder.stop();
			recorder.release();
		} catch (final FrameRecorder.Exception e) {
			logger.error("Error closing video {}", file.getPath(), e);
		}
	}
}
