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

package com.shootoff.camera.cameratypes;

import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.opencv.core.Mat;
import org.opencv.videoio.VideoCapture;
import org.opencv.videoio.Videoio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.sarxos.webcam.Webcam;
import com.shootoff.camera.CameraFactory;
import com.shootoff.camera.CameraManager;
import com.shootoff.camera.CameraView;
import com.shootoff.camera.Frame;
import com.shootoff.camera.shotdetection.JavaShotDetector;
import com.shootoff.camera.shotdetection.NativeShotDetector;
import com.shootoff.camera.shotdetection.ShotDetector;
import com.shootoff.util.SystemInfo;

public class SarxosCaptureCamera extends CalculatedFPSCamera {
	private static final Logger logger = LoggerFactory.getLogger(SarxosCaptureCamera.class);

	// V4L2's CAP_PROP_AUTO_EXPOSURE: 1 = manual, 3 = aperture priority (auto).
	private static final double V4L2_MANUAL_EXPOSURE = 1;

	private int cameraIndex = -1;
	private final VideoCapture camera;
	// Kept from when the camera was found: looking it up in the live webcam list by index again fails
	// once the camera is unplugged (the list shrinks under the index)
	private final String name;

	private final AtomicBoolean closing = new AtomicBoolean(false);

	// setViewSize is called before open() by CameraManager/CheckableImageListCell, and
	// VideoCapture.set ignores every property on an unopened capture, so the requested size is
	// stashed here and (re)applied once the camera is actually open.
	private Optional<Dimension> requestedViewSize = Optional.empty();

	// For testing
	protected SarxosCaptureCamera() {
		camera = null;
		name = null;
	}

	public SarxosCaptureCamera(final String cameraName) {
		final List<Webcam> webcams = Webcam.getWebcams();
		int cameraIndex = -1;

		for (int i = 0; i < webcams.size(); i++) {
			if (webcams.get(i).getName().equals(cameraName)) {
				cameraIndex = i;
				break;
			}
		}

		if (cameraIndex < 0) throw new IllegalArgumentException("Camera not found: " + cameraName);

		camera = new VideoCapture();
		this.cameraIndex = cameraIndex;
		name = cameraName;
	}

	public SarxosCaptureCamera(final String cameraName, int cameraIndex) {
		if (cameraIndex < 0) throw new IllegalArgumentException("Camera not found: " + cameraName);

		camera = new VideoCapture();
		this.cameraIndex = cameraIndex;
		name = cameraName;
	}

	@Override
	public Frame getFrame() {
		final Mat frame = new Mat();
		try {
			if (!isOpen() || !camera.read(frame) || frame.size().height == 0 || frame.size().width == 0) return null;
		} catch (final Exception e) {
			// Sometimes there is a race condition on closing the camera vs.
			// read()
			return null;
		}

		final long currentFrameTimestamp = System.currentTimeMillis();
		frameCount++;
		return new Frame(frame, currentFrameTimestamp);
	}

	@Override
	public BufferedImage getBufferedImage() {
		final Frame frame = getFrame();

		if (frame == null) {
			return null;
		} else {
			return frame.getOriginalBufferedImage();
		}
	}

	@Override
	public synchronized boolean open() {
		if (logger.isTraceEnabled())
			logger.trace("{} - open request isOpen {} closing {}", getName(), isOpen(), closing);

		if (isOpen() && !closing.get()) return true;

		closing.set(false);

		// V4L2 is the only backend that can negotiate a specific pixel format (YUYV/MJPG) on Linux
		final boolean open = SystemInfo.isLinux() ? camera.open(cameraIndex, Videoio.CAP_V4L2)
				: camera.open(cameraIndex);

		if (open) {
			applyCaptureSettings(camera, requestedViewSize);

			// setViewSize normally runs before open(), when VideoCapture.set is a no-op; apply
			// whatever size was requested now that the capture is actually open.
			if (requestedViewSize.isPresent()) applyViewSize(camera, requestedViewSize.get());

			// OpenCV opens camera index N as /dev/videoN
			if (SystemInfo.isLinux()) disableDynamicFramerate("/dev/video" + cameraIndex);

			// Logged after resolution is applied so this reports what's actually negotiated.
			logCaptureSettings(camera);

			CameraFactory.openCamerasAdd(this);
		}

		return open;
	}

	/**
	 * Many UVC webcams (e.g. the Logitech C270) let auto exposure halve the
	 * frame rate in dim light, and some power on with that enabled. OpenCV
	 * can't reach this control, so it's set directly through V4L2.
	 *
	 * @return true if the camera had dynamic frame rate enabled and it was
	 *         turned off
	 */
	static boolean disableDynamicFramerate(final String device) {
		final OptionalInt current = V4l2Controls.getControl(device, V4l2Controls.EXPOSURE_DYNAMIC_FRAMERATE);

		if (!current.isPresent()) {
			logger.debug("{} has no exposure_dynamic_framerate control", device);
			return false;
		}

		if (current.getAsInt() == 0) return false;

		final boolean changed = V4l2Controls.setControl(device, V4l2Controls.EXPOSURE_DYNAMIC_FRAMERATE, 0);

		if (changed) {
			logger.info("{} exposure_dynamic_framerate was {}, set to 0 so auto exposure can't lower the frame rate",
					device, current.getAsInt());
		} else {
			logger.warn("Could not turn off exposure_dynamic_framerate on {}; the frame rate may drop in dim light",
					device);
		}

		return changed;
	}

	// Uncompressed YUYV at high resolutions exceeds USB 2.0 bandwidth, which holds many webcams
	// to a few FPS, so MJPG is requested above 640x480. At or below that size YUYV is requested
	// explicitly instead of leaving it to the camera's default: it avoids MJPG's JPEG chroma
	// blurring of small colored dots (worse for laser detection) and, on cameras like the
	// Logitech C270, a per-frame libjpeg "Corrupt JPEG data" warning that MJPG triggers even at
	// low resolutions. Cameras that do not support the requested format keep their default.
	private static final int MAX_YUYV_PIXELS = 640 * 480;

	static int preferredFourcc(final Optional<Dimension> requestedSize) {
		if (requestedSize.isPresent()) {
			final Dimension size = requestedSize.get();
			if (size.getWidth() * size.getHeight() > MAX_YUYV_PIXELS) {
				return org.opencv.videoio.VideoWriter.fourcc('M', 'J', 'P', 'G');
			}
		}

		// No size requested means OpenCV's V4L2 default of 640x480 applies, which also gets YUYV.
		return org.opencv.videoio.VideoWriter.fourcc('Y', 'U', 'Y', 'V');
	}

	static void applyFourcc(final VideoCapture capture, final Optional<Dimension> requestedSize) {
		// FOURCC must be set before width/height are applied; V4L2 uses it to pick which of a
		// resolution's supported formats to negotiate.
		capture.set(Videoio.CAP_PROP_FOURCC, preferredFourcc(requestedSize));
	}

	static void applyCaptureSettings(final VideoCapture capture, final Optional<Dimension> requestedSize) {
		applyFourcc(capture, requestedSize);

		// Set the max FPS to 60. If we don't set this it defaults
		// to 30, which unnecessarily hampers higher end cameras
		capture.set(Videoio.CAP_PROP_FPS, 60);
	}

	static void applyViewSize(final VideoCapture capture, final Dimension size) {
		capture.set(Videoio.CAP_PROP_FRAME_WIDTH, size.getWidth());
		capture.set(Videoio.CAP_PROP_FRAME_HEIGHT, size.getHeight());
	}

	static void logCaptureSettings(final VideoCapture capture) {
		logger.info("Camera capture negotiated {}x{} {} at {} FPS", (int) capture.get(Videoio.CAP_PROP_FRAME_WIDTH),
				(int) capture.get(Videoio.CAP_PROP_FRAME_HEIGHT), fourccToString(capture.get(Videoio.CAP_PROP_FOURCC)),
				capture.get(Videoio.CAP_PROP_FPS));
	}

	static String fourccToString(final double fourcc) {
		final int code = (int) fourcc;
		return new String(new char[] { (char) (code & 0xFF), (char) ((code >> 8) & 0xFF),
				(char) ((code >> 16) & 0xFF), (char) ((code >> 24) & 0xFF) });
	}

	@Override
	public boolean isOpen() {
		return camera.isOpened();
	}

	@Override
	public synchronized void close() {
		if (logger.isTraceEnabled())
			logger.trace("{} - close request isOpen {} closing {}", getName(), isOpen(), closing);

		if (isOpen() && !closing.get()) {
			closing.set(true);
			resetExposure();
			camera.release();

			CameraFactory.openCamerasRemove(this);
			if (cameraEventListener.isPresent()) cameraEventListener.get().cameraClosed();
			
		} else if (isOpen() && closing.get()) {
			return;
		} else if (!isOpen()) {
			closing.set(false);
		}

		return;
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	public void setViewSize(final Dimension size) {
		requestedViewSize = Optional.of(size);

		// Callers (CameraManager, CheckableImageListCell) call this before open(), when
		// VideoCapture.set is a no-op; open() applies the stashed size once the camera is open.
		// If the camera is already open, apply it immediately instead of waiting for a reopen.
		// The fourcc is re-picked for the new size and set before width/height, same V4L2 order
		// as open().
		if (isOpen()) {
			applyFourcc(camera, requestedViewSize);
			applyViewSize(camera, size);
		}
	}

	@Override
	public Dimension getViewSize() {
		return new Dimension((int) camera.get(Videoio.CAP_PROP_FRAME_WIDTH),
				(int) camera.get(Videoio.CAP_PROP_FRAME_HEIGHT));
	}

	public void launchCameraSettings() {
		camera.set(Videoio.CAP_PROP_SETTINGS, 1);
	}

	@Override
	public ShotDetector getPreferredShotDetector(final CameraManager cameraManager, final CameraView cameraView) {
		if (NativeShotDetector.isSystemSupported())
			return new NativeShotDetector(cameraManager, cameraView);
		else if (JavaShotDetector.isSystemSupported())
			return new JavaShotDetector(cameraManager, cameraView);
		else
			return null;
	}

	// Frame-processing exception types already logged by run(), so a
	// repeating failure (e.g. a broken recorder/shot-detector pipeline)
	// can't flood the log every frame.
	private final Set<Class<?>> loggedFrameExceptionTypes = new HashSet<>();

	@Override
	public void run() {
		while (isOpen() && !closing.get()) {
			try {
				if (cameraEventListener.isPresent()) cameraEventListener.get().newFrame(getFrame());

				if (((int) (getFrameCount() % Math.min(getFPS(), 5)) == 0) && cameraState != CameraState.CALIBRATING) {
					estimateCameraFPS();
				}
			} catch (final RuntimeException e) {
				// A failure processing one frame (e.g. in the shot detector
				// or recorder pipeline reached via the listener) must not
				// kill this thread and freeze the feed.
				if (loggedFrameExceptionTypes.add(e.getClass())) {
					logger.error("Unexpected exception processing a frame from {}; continuing", getName(), e);
				}
			}

		}

		if (logger.isTraceEnabled())
			logger.trace("{} camera closed during run thread isOpen {} closing {}", getName(), isOpen(), closing);

		if (!closing.get()) close();
	}

	@Override
	public boolean isLocked() {
		return false;
	}

	private Optional<Double> origExposure = Optional.empty();
	private Optional<Double> origAutoExposure = Optional.empty();
	private boolean manualExposureActive = false;

	@Override
	public synchronized boolean supportsExposureAdjustment() {
		// If we already verified that it works,
		// we have an origExposure value set
		if (origExposure.isPresent()) return true;

		final double exp = camera.get(Videoio.CAP_PROP_EXPOSURE);

		if (logger.isInfoEnabled()) logger.info("Initial camera exposure {}", exp);

		if (exp == 0) return false;

		origExposure = Optional.of(exp);

		if (!decreaseExposure()) {
			resetExposure();
			origExposure = Optional.empty();
			return false;
		}

		resetExposure();
		return true;
	}

	// V4L2 rejects direct writes to CAP_PROP_EXPOSURE while CAP_PROP_AUTO_EXPOSURE is in an auto
	// mode (e.g. 3 = aperture priority). Switch to manual (1) first, remembering the original
	// auto-exposure value so resetExposure() can restore it. Other backends (macOS, Windows, IP
	// cameras) don't share V4L2's auto-exposure values/semantics and are left untouched.
	private synchronized boolean switchToManualExposure() {
		if (!SystemInfo.isLinux() || manualExposureActive) return true;

		final double autoExposure = camera.get(Videoio.CAP_PROP_AUTO_EXPOSURE);

		if (!camera.set(Videoio.CAP_PROP_AUTO_EXPOSURE, V4L2_MANUAL_EXPOSURE)) return false;

		origAutoExposure = Optional.of(autoExposure);
		manualExposureActive = true;

		if (logger.isInfoEnabled())
			logger.info("{} switched to manual exposure (was auto={}) to allow exposure adjustment", getName(),
					autoExposure);

		return true;
	}

	@Override
	public synchronized boolean decreaseExposure() {
		// V4L2 must be in manual exposure mode before CAP_PROP_EXPOSURE writes are honored.
		if (!switchToManualExposure()) return false;

		// Logic:
		// If camera exposure is positive, decrease towards zero
		// If camera exposure is negative and between -9.9 and 0, increase
		// towards zero (Logitech c270)
		// If camera exposure is negative and less than -10, decrease away from
		// zero (oCam)

		// In any case, if exposure doesn't change in the same direction when we
		// change it, fail out.
		final double curExp = camera.get(Videoio.CAP_PROP_EXPOSURE);
		final double newExp;
		if (curExp <= -10.0) {
			newExp = curExp + (.1 * curExp);
		} else {
			newExp = curExp - (.1 * curExp);
		}

		if (logger.isTraceEnabled()) logger.trace("curExp[ {} newExp {}", curExp, newExp);

		// If they don't have the same sign, ABORT
		if (!((curExp < 0) == (newExp < 0)) || Math.abs(curExp - newExp) < .001f) return false;

		camera.set(Videoio.CAP_PROP_EXPOSURE, newExp);

		if (logger.isTraceEnabled()) logger.trace("Reducing exposure - curExp[ {} newExp {} res {}", curExp, newExp,
				camera.get(Videoio.CAP_PROP_EXPOSURE));

		if (curExp <= -10.0)
			return (camera.get(Videoio.CAP_PROP_EXPOSURE) < curExp);
		else
			return (Math.abs(camera.get(Videoio.CAP_PROP_EXPOSURE)) < Math.abs(curExp));
	}

	@Override
	public synchronized void resetExposure() {
		// Set exposure while still in manual mode -- V4L2 rejects it once auto mode is restored.
		if (origExposure.isPresent()) camera.set(Videoio.CAP_PROP_EXPOSURE, origExposure.get());

		if (manualExposureActive && origAutoExposure.isPresent()) {
			final double autoExposure = origAutoExposure.get();
			camera.set(Videoio.CAP_PROP_AUTO_EXPOSURE, autoExposure);
			manualExposureActive = false;

			if (logger.isInfoEnabled())
				logger.info("{} restored auto exposure mode to {}", getName(), autoExposure);
		}
	}

	@Override
	public synchronized OptionalDouble manualExposure() {
		return manualExposureActive ? OptionalDouble.of(camera.get(Videoio.CAP_PROP_EXPOSURE)) : OptionalDouble.empty();
	}

	@Override
	public synchronized void restoreManualExposure(double exposure) {
		if (switchToManualExposure()) camera.set(Videoio.CAP_PROP_EXPOSURE, exposure);
	}

	@Override
	public boolean limitsFrames() {
		return false;
	}
}
