package com.shootoff.camera;

import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.shootoff.camera.cameratypes.Camera;
import com.shootoff.camera.cameratypes.CameraEventListener;
import com.shootoff.camera.shotdetection.JavaShotDetector;
import com.shootoff.camera.shotdetection.ShotDetector;
import com.shootoff.camera.video.TimedFrame;
import com.shootoff.camera.video.VideoReader;

public class MockCamera implements Camera {
	protected static final Logger logger = LoggerFactory.getLogger(MockCamera.class);

	protected File videoFile;
	protected long lastVideoTimestamp = -1;
	protected static final int SECOND_IN_MICROSECONDS = 1000 * 1000;
	protected Optional<CameraEventListener> cameraEventListener = Optional.empty();

	public MockCamera() {}

	public MockCamera(File videoFile) {
		this.videoFile = videoFile;
	}

	@Override
	public boolean isOpen() {
		return true;
	}

	@Override
	public String getName() {
		return "MockCamera";
	}

	@Override
	public void run() {
		if (videoFile == null) return;

		logger.trace("opening {}", videoFile.getAbsolutePath());

		try (VideoReader reader = new VideoReader(videoFile)) {
			Optional<TimedFrame> frame;
			while ((frame = reader.next()).isPresent())
				onVideoFrame(frame.get());
		} catch (final IOException e) {
			logger.error("Error reading mock camera video {}", videoFile.getAbsolutePath(), e);
		}

		if (cameraEventListener.isPresent()) cameraEventListener.get().cameraClosed();
	}

	private long initialSystemTimeAtVideoStart = -1;
	protected long currentFrameTimestamp = -1;
	private int frameCount = 0;
	public static final int DEFAULT_FPS = 30;
	private double webcamFPS = 0.0;

	private void onVideoFrame(TimedFrame frame) {
		final BufferedImage currentFrame = frame.getImage();

		if (initialSystemTimeAtVideoStart == -1) initialSystemTimeAtVideoStart = System.currentTimeMillis();

		currentFrameTimestamp = frame.getTimestampMs() + initialSystemTimeAtVideoStart;

		if (frameCount == 0 && cameraEventListener.isPresent()) {
			setViewSize(new Dimension(currentFrame.getWidth(), currentFrame.getHeight()));
			cameraEventListener.get().setFeedResolution(currentFrame.getWidth(), currentFrame.getHeight());
		}

		if (lastVideoTimestamp > -1 && (frameCount % 30) == 0) {
			final double estimateFPS = (double) SECOND_IN_MICROSECONDS
					/ (double) (frame.getTimestampMicros() - lastVideoTimestamp);

			setFPS(estimateFPS);
		}
		lastVideoTimestamp = frame.getTimestampMicros();

		if (cameraEventListener.isPresent())
			cameraEventListener.get().newFrame(new Frame(Camera.bufferedImageToMat(currentFrame), currentFrameTimestamp));

		frameCount++;
	}

	protected void setFPS(double newFPS) {
		// This just tells us if it's the first FPS estimate
		if (getFrameCount() > DEFAULT_FPS)
			webcamFPS = ((webcamFPS * 4.0) + newFPS) / 5.0;
		else
			webcamFPS = newFPS;
	}

	@Override
	public Frame getFrame() {
		return null;
	}

	@Override
	public BufferedImage getBufferedImage() {
		return null;
	}

	@Override
	public boolean open() {
		return false;
	}

	@Override
	public void close() {}

	@Override
	public void setCameraEventListener(CameraEventListener cameraEventListener) {
		this.cameraEventListener = Optional.of(cameraEventListener);
	}

	@Override
	public int getFrameCount() {
		return frameCount;
	}

	@Override
	public ShotDetector getPreferredShotDetector(CameraManager cameraManager, CameraView cameraView) {
		if (JavaShotDetector.isSystemSupported())
			return new JavaShotDetector(cameraManager, cameraView);
		else
			return null;
	}

	@Override
	public boolean isLocked() {
		return false;
	}

	private Dimension size = null;

	@Override
	public void setViewSize(Dimension size) {
		this.size = size;
	}

	@Override
	public Dimension getViewSize() {
		return size;
	}

	@Override
	public double getFPS() {
		return webcamFPS;
	}

	@Override
	public boolean setState(CameraState state) {
		return true;
	}

	@Override
	public CameraState getState() {
		return CameraState.DETECTING;
	}

	@Override
	public boolean supportsExposureAdjustment() {
		return false;
	}

	@Override
	public boolean decreaseExposure() {
		return false;
	}

	@Override
	public void resetExposure() {}

	@Override
	public boolean limitsFrames() {
		return false;
	}
}
