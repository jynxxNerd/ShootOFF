package com.shootoff.camera;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import com.shootoff.camera.shot.DisplayShot;
import com.shootoff.geom.Rect;

/**
 * A camera view with no UI that records what the camera pipeline sends it.
 */
public class RecordingCameraView implements CameraView {
	private final List<String> diagnosticWarnings = new CopyOnWriteArrayList<>();
	private final Semaphore removedWarnings = new Semaphore(0);
	private final BlockingQueue<DisplayShot> shots = new LinkedBlockingQueue<>();

	@Override
	public void addShot(DisplayShot shot, boolean isMirroredShot) {
		shots.add(shot);
	}

	@Override
	public DiagnosticMessage addDiagnosticWarning(String message) {
		diagnosticWarnings.add(message);
		return removedWarnings::release;
	}

	@Override
	public void clearShots() {}

	@Override
	public void close() {}

	@Override
	public void reset() {}

	@Override
	public void setCameraManager(CameraManager cameraManager) {}

	@Override
	public void updateBackground(BufferedImage frame, Optional<Rect> projectionBounds) {}

	public List<String> diagnosticWarnings() {
		return List.copyOf(diagnosticWarnings);
	}

	public boolean awaitRemovedWarnings(int count, long timeout, TimeUnit unit) throws InterruptedException {
		return removedWarnings.tryAcquire(count, timeout, unit);
	}

	public Optional<DisplayShot> awaitShot(long timeout, TimeUnit unit) throws InterruptedException {
		return Optional.ofNullable(shots.poll(timeout, unit));
	}
}
