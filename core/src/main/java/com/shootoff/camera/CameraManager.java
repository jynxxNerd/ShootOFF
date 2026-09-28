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

package com.shootoff.camera;

import java.awt.Dimension;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import org.opencv.core.CvException;
import org.opencv.core.Mat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.shootoff.ObservableCloseable;
import com.shootoff.calibration.CalibrationCamera;
import com.shootoff.camera.autocalibration.AutoCalibrationManager;
import com.shootoff.camera.cameratypes.Camera;
import com.shootoff.camera.cameratypes.Camera.CameraState;
import com.shootoff.camera.cameratypes.CameraEventListener;
import com.shootoff.camera.cameratypes.SarxosCaptureCamera;
import com.shootoff.camera.processors.DeduplicationProcessor;
import com.shootoff.camera.recorders.RollingRecorder;
import com.shootoff.camera.recorders.ShotRecorder;
import com.shootoff.camera.shot.ShotColor;
import com.shootoff.camera.shotdetection.CameraStateListener;
import com.shootoff.camera.shotdetection.FrameProcessingShotDetector;
import com.shootoff.camera.shotdetection.JavaShotDetector;
import com.shootoff.camera.shotdetection.ShotDetector;
import com.shootoff.camera.shotdetection.ShotYieldingShotDetector;
import com.shootoff.camera.video.VideoWriter;
import com.shootoff.config.Settings;
import com.shootoff.util.TimerPool;

import com.shootoff.geom.Rect;
import com.shootoff.geom.Size;

/**
 * This class is responsible for fetching frames from its assigned camera and
 * preprocessing them for shot detection. It also ensures the view showing the
 * camera frames is aware of any new frames from the camera.
 *
 * @author phrack and dmaul
 */
public class CameraManager
		implements ObservableCloseable, CameraEventListener, CameraCalibrationListener, CalibrationCamera {
	private static final int MAXIMUM_CONSECUTIVE_CAMERA_ERRORS = 5;
	private static final Logger logger = LoggerFactory.getLogger(CameraManager.class);
	public static final int DEFAULT_FEED_WIDTH = 640;
	public static final int DEFAULT_FEED_HEIGHT = 480;
	public static final int MIN_SHOT_DETECTION_FPS = 5;

	protected int feedWidth = DEFAULT_FEED_WIDTH;
	protected int feedHeight = DEFAULT_FEED_HEIGHT;

	protected final static int DIAGNOSTIC_MESSAGE_DURATION = 1000; // ms

	protected Optional<CameraDebuggerListener> debuggerListener = Optional.empty();

	protected final ShotDetector shotDetector;
	private long startTime = 0;

	protected final Camera camera;
	private final Optional<CameraErrorView> cameraErrorView;
	private Optional<CloseListener> closeListener = Optional.empty();

	protected final CameraView cameraView;
	protected final Settings config = Settings.getSettings();
	private final Object projectionBoundsLock = new Object();
	protected Optional<Rect> projectionBounds = Optional.empty();

	private final AtomicBoolean isStreaming = new AtomicBoolean(true);
	private final AtomicBoolean isDetectionLocked = new AtomicBoolean(false);
	private final AtomicBoolean isDetecting = new AtomicBoolean(true);
	private final AtomicBoolean isCalibrating = new AtomicBoolean(false);
	private boolean shownBrightnessWarning = false;
	private boolean cropFeedToProjection = false;
	private boolean limitDetectProjection = false;

	protected Optional<Integer> minimumShotDimension = Optional.empty();

	protected volatile boolean recordingStream = false;
	protected volatile VideoWriter videoWriterStream;
	protected long recordingStartTime;

	protected volatile boolean recordingShots = false;
	protected volatile RollingRecorder rollingRecorder;
	protected Map<Shot, ShotRecorder> shotRecorders = new ConcurrentHashMap<>();

	protected boolean[][] sectorStatuses;

	private boolean showedFPSWarning = false;

	// A new one for each calibration (enableAutoCalibration), so the one that found the current warp is kept
	// whole for saveCalibration while the next one looks; read on the camera's thread
	protected volatile AutoCalibrationManager acm = null;
	private final AtomicBoolean isAutoCalibrating = new AtomicBoolean(false);
	protected volatile boolean cameraAutoCalibrated = false;

	protected final DeduplicationProcessor deduplicationProcessor = new DeduplicationProcessor(this);

	private CameraCalibrationListener cameraCalibrationListener;

	public void setCalibrationManager(CameraCalibrationListener calibrationManager) {
		cameraCalibrationListener = calibrationManager;
	}

	public DeduplicationProcessor getDeduplicationProcessor() {
		return deduplicationProcessor;
	}

	public CameraManager() {
		camera = null;
		cameraErrorView = Optional.empty();
		cameraView = null;
		shotDetector = null;
	}

	public CameraManager(Camera cameraInterface, CameraErrorView cameraErrorView, CameraView view) {

		camera = cameraInterface;

		this.cameraErrorView = Optional.ofNullable(cameraErrorView);
		cameraView = view;

		cameraView.setCameraManager(this);

		camera.setCameraEventListener(this);

		shotDetector = camera.getPreferredShotDetector(this, view);

		if (shotDetector == null) logger.error("No suitable shot detector found for camera {}", camera.getName());

	}

	public String getName() {
		return camera.getName();
	}

	public boolean start() {
		sectorStatuses = new boolean[JavaShotDetector.SECTOR_ROWS][JavaShotDetector.SECTOR_COLUMNS];

		// Turn on all shot sectors by default
		for (int x = 0; x < JavaShotDetector.SECTOR_COLUMNS; x++) {
			for (int y = 0; y < JavaShotDetector.SECTOR_ROWS; y++) {
				sectorStatuses[y][x] = true;
			}
		}

		synchronized (camera) {
			if (!camera.isOpen()) {
				camera.setViewSize(new Dimension(getFeedWidth(), getFeedHeight()));

				if (!camera.open()) return false;

				final Dimension openDimension = camera.getViewSize();

				if ((int) openDimension.getWidth() != getFeedWidth()
						|| (int) openDimension.getHeight() != getFeedHeight()) {
					if (openDimension.getWidth() == -1) return false;

					if (logger.isWarnEnabled()) {
						logger.warn(
								"Camera {} dimension differs from requested dimensions, requested {} {} actual {} {}",
								getName(), getFeedWidth(), getFeedHeight(), (int) openDimension.getWidth(),
								(int) openDimension.getHeight());
					}

					setFeedResolution((int) openDimension.getWidth(), (int) openDimension.getHeight());
					shotDetector.setFrameSize((int) openDimension.getWidth(), (int) openDimension.getHeight());
				} else {
					setFeedResolution((int) openDimension.getWidth(), (int) openDimension.getHeight());
				}
			}

			if (logger.isDebugEnabled()) logger.debug("starting camera thread {}", camera.getName());
			final String threadName = String.format("Camera %s %s", camera.getName(),
					shotDetector.getClass().getSimpleName());
			new Thread(camera, threadName).start();

		}

		if (shotDetector instanceof ShotYieldingShotDetector)
			((ShotYieldingShotDetector) shotDetector).initDetecting();

		setDetecting(true);

		return true;
	}

	public boolean isSectorOn(int x, int y) {
		return sectorStatuses[y][x];
	}

	public void setSectorStatuses(boolean[][] sectorStatuses) {
		if (sectorStatuses == null) return;

		this.sectorStatuses = new boolean[sectorStatuses.length][sectorStatuses[0].length];

		for (int i = 0; i < sectorStatuses.length; i++) {
			System.arraycopy(sectorStatuses[i], 0, this.sectorStatuses[i], 0, sectorStatuses[i].length);
		}
	}

	public int getFeedWidth() {
		return feedWidth;
	}

	public int getFeedHeight() {
		return feedHeight;
	}

	// TODO: This doesn't handle potential side effects of modifying the feed
	// resolution on the fly.
	@Override
	public void setFeedResolution(int width, int height) {
		feedWidth = width;
		feedHeight = height;
		shotDetector.setFrameSize(width, height);
	}

	// Used by click-to-shoot and tests to inject a shot via the shot detector
	public void injectShot(ShotColor color, double x, double y, boolean scaleShot) {
		shotDetector.addShot(color, x, y, System.currentTimeMillis(), scaleShot);
	}

	public void clearShots() {
		cameraView.clearShots();
	}

	public void reset() {
		resetStartTime(0);
		shotDetector.reset();
		deduplicationProcessor.reset();
		cameraView.reset();
	}

	private void resetStartTime(long timestamp) {
		startTime = timestamp;
	}

	@Override
	public void setOnCloseListener(CloseListener closeListener) {
		this.closeListener = Optional.of(closeListener);
	}

	@Override
	public void close() {
		getCameraView().close();
		setDetecting(false);
		setStreaming(false);

		setCameraState(CameraState.CLOSED);
		
		camera.setCameraEventListener(null);


		if (recordingStream) stopRecordingStream();
		TimerPool.cancelTimer(brightnessDiagnosticFuture);
		TimerPool.cancelTimer(motionDiagnosticFuture);

		if (recordingCalibratedArea) stopRecordingCalibratedArea();

		if (closeListener.isPresent()) closeListener.get().closing();
	}

	public void setStreaming(boolean isStreaming) {
		this.isStreaming.set(isStreaming);
	}

	// Sometimes it is useful to ensure that a camera that isn't detecting
	// states not detecting (e.g. to not be turned back on by reset button
	public void setDetectionLockState(boolean isLocked) {
		isDetectionLocked.set(isLocked);
	}

	List<CameraStateListener> cameraStateListeners = new ArrayList<>();

	public void registerCameraStateListener(CameraStateListener csl) {
		cameraStateListeners.add(csl);
	}

	private void setCameraState(CameraState state) {
		if (camera.setState(state)) for (final CameraStateListener csl : cameraStateListeners)
			csl.cameraStateChange(state);
	}

	public void setDetecting(boolean isDetecting) {
		if (isDetectionLocked.get()) {
			logger.debug("Attempted to set detection for {} to {}, but the detection state is locked.", getName(),
					isDetecting);
			return;
		}

		// Lock this to false during calibration
		if (isCalibrating.get() && isDetecting) {
			logger.info("Not changing detection to true during calibration");
			return;
		}

		if (logger.isTraceEnabled())
			logger.trace("setDetecting was {} now {} for {}", this.isDetecting, isDetecting, getName());

		if (isDetecting && projectionBounds.isPresent())
			setCameraState(CameraState.DETECTING_CALIBRATED);
		else if (isDetecting)
			setCameraState(CameraState.DETECTING);
		else
			setCameraState(CameraState.NORMAL);
		this.isDetecting.set(isDetecting);
	}

	public void setCalibrating(final boolean isCalibrating) {

		this.isCalibrating.set(isCalibrating);
		if (isCalibrating) {
			setCameraState(CameraState.CALIBRATING);
			setDetecting(false);
		}
	}

	public boolean isDetecting() {
		return isDetecting.get();
	}

	public void setProjectionBounds(final Rect projectionBounds) {
		synchronized (projectionBoundsLock) {
			this.projectionBounds = Optional.ofNullable(projectionBounds);
		}
	}

	public void setCropFeedToProjection(final boolean cropFeed) {
		cropFeedToProjection = cropFeed;
	}

	public void setLimitDetectProjection(final boolean limitDetection) {
		limitDetectProjection = limitDetection;
	}

	public boolean isCroppingFeedToProjection() {
		return cropFeedToProjection;
	}

	public boolean isLimitingDetectionToProjection() {
		return limitDetectProjection;
	}

	public Optional<Rect> getProjectionBounds() {
		return projectionBounds;
	}

	public boolean startRecordingStream(File videoFile) {
		if (logger.isDebugEnabled()) logger.debug("Writing Video Feed To: {}", videoFile.getAbsoluteFile());

		try {
			videoWriterStream = new VideoWriter(videoFile, getFeedWidth(), getFeedHeight());
		} catch (final IOException e) {
			logger.error("Could not start recording the video feed to {}", videoFile.getAbsolutePath(), e);
			return false;
		}

		recordingStartTime = System.currentTimeMillis();
		recordingStream = true;

		return true;
	}

	public void stopRecordingStream() {
		if (!recordingStream || videoWriterStream == null) return;

		recordingStream = false;
		videoWriterStream.close();
		videoWriterStream = null;
	}

	public void notifyShot(final Shot shot) {
		if (rollingRecorder != null) rollingRecorder.fork().ifPresent(recorder -> shotRecorders.put(shot, recorder));
	}

	public ShotRecorder getRevelantRecorder(Shot shot) {
		return shotRecorders.get(shot);
	}

	/**
	 * Starts keeping video around each detected shot.
	 *
	 * @param sessionName
	 *            the name of the session being recorded, if any; shot videos then go in a
	 *            folder of that name under <tt>sessions/</tt>
	 */
	public void startRecordingShots(Optional<String> sessionName) {
		String sessionFolderName = null;
		if (sessionName.isPresent()) {
			sessionFolderName = sessionName.get();

			final File sessionVideoFolder = new File(System.getProperty("shootoff.home") + File.separator + "sessions"
					+ File.separator + sessionName.get());

			if (!sessionVideoFolder.exists() && !sessionVideoFolder.mkdirs()) {
				logger.error("Could not create video folder for session: {}", sessionVideoFolder.getAbsolutePath());
			}
		}

		String cameraName = "UNNAMED";

		final Optional<String> userCameraName = config.getWebcamsUserName(camera);

		if (userCameraName.isPresent()) {
			cameraName = userCameraName.get();
		} else {
			cameraName = camera.getName();
		}

		setDetecting(false);

		try {
			rollingRecorder = new RollingRecorder(".mp4", sessionFolderName, cameraName, getFeedWidth(), getFeedHeight());
			recordingShots = true;
		} catch (final IOException e) {
			logger.error("Could not start recording shots for camera {}", cameraName, e);
			setDetecting(true);
		}
	}

	public void stopRecordingShots() {
		recordingShots = false;
		for (final ShotRecorder r : shotRecorders.values())
			r.close();
		shotRecorders.clear();
		if (rollingRecorder != null) {
			rollingRecorder.close();
			rollingRecorder = null;
		}

		setDetecting(true);
	}

	public Camera getCamera() {
		return camera;
	}

	public BufferedImage getCurrentFrame() {
		return camera.getBufferedImage();
	}

	public CameraView getCameraView() {
		return cameraView;
	}

	public void setMinimumShotDimension(int minDim) {
		minimumShotDimension = Optional.of(minDim);
		logger.debug("Set the minimum dimension for shots to: {}", minDim);
	}

	public Optional<Integer> getMinimumShotDimension() {
		return minimumShotDimension;
	}

	public void setThresholdListener(CameraDebuggerListener thresholdListener) {
		debuggerListener = Optional.ofNullable(thresholdListener);
	}

	public Optional<CameraDebuggerListener> getDebuggerListener() {
		return debuggerListener;
	}

	private ScheduledFuture<?> brightnessDiagnosticFuture = null;
	private ScheduledFuture<?> motionDiagnosticFuture = null;

	private boolean recordCalibratedArea = false;
	private volatile VideoWriter videoWriterCalibratedArea;
	private long recordingCalibratedAreaStartTime;
	private volatile boolean recordingCalibratedArea;

	public void startRecordingCalibratedArea(File videoFile, int width, int height) {
		if (logger.isDebugEnabled()) logger.debug("Writing Video Feed To: {}", videoFile.getAbsoluteFile());

		try {
			videoWriterCalibratedArea = new VideoWriter(videoFile, width, height);
		} catch (final IOException e) {
			logger.error("Could not start recording the calibrated area to {}", videoFile.getAbsolutePath(), e);
			return;
		}

		recordingCalibratedAreaStartTime = System.currentTimeMillis();
		recordingCalibratedArea = true;
	}

	public void stopRecordingCalibratedArea() {
		if (!recordingCalibratedArea || videoWriterCalibratedArea == null) return;

		recordingCalibratedArea = false;
		videoWriterCalibratedArea.close();
		videoWriterCalibratedArea = null;
	}

	@Override
	public void newFrame(Frame frame) {
		newFrame(frame, true);
	}

	@Override
	public void newFrame(Frame frame, boolean shouldDedistort) {
		if (!handleFrame(frame, shouldDedistort)) logger.warn("Invalid frame yielded from {}", camera.getName());
	}

	private int consecutiveCameraErrors = 0;

	private boolean handleFrame(Frame currentFrame, boolean shouldDedistort) {
		boolean cameraError = false;

		if (currentFrame == null && !camera.isOpen()) {
			// Camera appears to have closed
			if (isStreaming.get() && cameraErrorView.isPresent()) cameraErrorView.get().showMissingCameraError(camera);

			cameraError = true;
		} else if (currentFrame == null && camera.isOpen()) {
			// Camera appears to be open but got a null frame
			logger.warn("Null frame from camera: {}", camera.getName());
			cameraError = true;
		} else if (currentFrame != null
				&& (currentFrame.size().height != feedHeight || currentFrame.size().width != feedWidth)
				&& camera.isOpen()) {
			// Camera appears to be open but got an invalid size frame
			logger.warn("Invalid frame size from camera: {} gave {} expecting {},{}", camera.getName(),
					currentFrame.size(), feedWidth, feedHeight);
			cameraError = true;
		}

		if (cameraError) {
			consecutiveCameraErrors++;
			if (consecutiveCameraErrors > MAXIMUM_CONSECUTIVE_CAMERA_ERRORS) {
				if (isStreaming.get() && cameraErrorView.isPresent())
					cameraErrorView.get().showMissingCameraError(camera);

				camera.close();
			}
			return false;
		} else {
			consecutiveCameraErrors = 0;
		}

		BufferedImage currentImage = processFrame(currentFrame, shouldDedistort);

		Rect b;

		synchronized (projectionBoundsLock) {
			if (projectionBounds.isPresent()) {
				b = projectionBounds.get();
			} else {
				b = null;
			}
		}

		if (cropFeedToProjection && b != null) {
			currentImage = currentImage.getSubimage((int) b.getMinX(), (int) b.getMinY(), (int) b.getWidth(),
					(int) b.getHeight());
		}

		if (recordingShots) {
			final RollingRecorder recorder = rollingRecorder;

			if (recorder != null) recorder.recordFrame(currentImage);

			final List<Shot> removeKeys = new ArrayList<>();
			for (final Entry<Shot, ShotRecorder> r : shotRecorders.entrySet()) {
				if (r.getValue().isComplete()) {
					r.getValue().close();
					removeKeys.add(r.getKey());
				} else {
					r.getValue().recordFrame(currentImage);
				}
			}

			for (final Shot s : removeKeys)
				shotRecorders.remove(s);
		}

		if (recordingStream) {
			final VideoWriter writer = videoWriterStream;

			if (writer != null) {
				try {
					writer.write(currentImage, System.currentTimeMillis() - recordingStartTime);
				} catch (final IOException e) {
					logger.error("Failed to record video feed frame", e);
				}
			}
		}

		if (cropFeedToProjection && projectionBounds.isPresent()) {
			cameraView.updateBackground(currentImage, projectionBounds);
		} else {
			cameraView.updateBackground(currentImage, Optional.empty());
		}

		return true;
	}

	protected BufferedImage processFrame(Frame currentFrame, boolean shouldDedistort) {
		if (isAutoCalibrating.get()) {
			// Read once: Cancel on a camera that was never calibrated can restore acm to null between the
			// isAutoCalibrating check above and this read (restoreCalibration, which sets acm before the flag)
			final AutoCalibrationManager acm = this.acm;
			if (acm != null) acm.processFrame(currentFrame);
			// disableAutoCalibration (another thread) can land between the isAutoCalibrating check above and
			// the limit being taken just now: its release found nothing held, so release again
			if (!isAutoCalibrating.get()) camera.releaseExposureLimit();
			return currentFrame.getOriginalBufferedImage();
		}

		// Each read once, the flag first: Cancel can put an earlier warp back (restoreCalibration, which sets the
		// manager before the flag) while this frame is processed
		final boolean cameraAutoCalibrated = this.cameraAutoCalibrated;
		final AutoCalibrationManager acm = this.acm;

		Mat submatFrameBGR = null;

		Rect projectionBounds;

		synchronized (projectionBoundsLock) {
			if (this.projectionBounds.isPresent()) {
				projectionBounds = this.projectionBounds.get();
			} else {
				projectionBounds = null;
			}
		}

		if (cameraAutoCalibrated && projectionBounds != null) {
			if (shouldDedistort && acm != null) {
				// MUST BE IN BGR pixel format.
				currentFrame = acm.undistortFrame(currentFrame);
			}

			try {
				submatFrameBGR = currentFrame.getOriginalMat().submat((int) projectionBounds.getMinY(),
						(int) projectionBounds.getMaxY(), (int) projectionBounds.getMinX(),
						(int) projectionBounds.getMaxX());
			} catch (CvException e) {
				logger.error("Failed to get submat for frame using projection bounds, projectionBounds = "
						+ projectionBounds.toString() + ", frameSize = "
						+ currentFrame.getOriginalMat().size().toString(), e);
			}
			
			if (recordingCalibratedArea) {
				final VideoWriter writer = videoWriterCalibratedArea;

				if (writer != null) {
					try {
						writer.write(Camera.matToBufferedImage(submatFrameBGR),
								System.currentTimeMillis() - recordingCalibratedAreaStartTime);
					} catch (final IOException e) {
						logger.error("Failed to record calibrated area frame", e);
					}
				}
			}

			if (debuggerListener.isPresent()) {
				debuggerListener.get().updateDebugView(Camera.matToBufferedImage(submatFrameBGR));
			}
		}

		if ((isLimitingDetectionToProjection() || isCroppingFeedToProjection()) && projectionBounds != null) {
			if (submatFrameBGR == null) {
				try {
					submatFrameBGR = currentFrame.getOriginalMat().submat((int) projectionBounds.getMinY(),
							(int) projectionBounds.getMaxY(), (int) projectionBounds.getMinX(),
							(int) projectionBounds.getMaxX());
				} catch (CvException e) {
					logger.error("Failed to get submat for frame to limit detection bounds, projectionBounds = "
							+ projectionBounds.toString() + ", frameSize = "
							+ currentFrame.getOriginalMat().size().toString(), e);
				}
			}

			if (shotDetector instanceof FrameProcessingShotDetector) {
				if (submatFrameBGR != null) {
					((FrameProcessingShotDetector) shotDetector)
							.processFrame(new Frame(submatFrameBGR, currentFrame.getTimestamp()), isDetecting.get());
				} else {
					logger.warn("Due to errors fetching frame submat, falling back to using full frame");
					((FrameProcessingShotDetector) shotDetector).processFrame(currentFrame, isDetecting.get());
				}
			}
		} else {
			if (shotDetector instanceof FrameProcessingShotDetector)
				((FrameProcessingShotDetector) shotDetector).processFrame(currentFrame, isDetecting.get());
		}

		// currentFrame is showing the colored pixels for brightness and motion,
		// hence why we need to return the converted version
		return currentFrame.getOriginalBufferedImage();
	}

	private void checkIfMinimumFPS(double cameraFPS) {
		if (cameraFPS < MIN_SHOT_DETECTION_FPS && !showedFPSWarning) {
			logger.warn("[{}] Current webcam FPS is {}, which is too low for reliable shot detection", camera.getName(),
					getFPS());
			// A dark scene (a long exposure) or a slow start? (spec §8 Revision 4, decision 3)
			final String exposure = camera.exposureState();
			if (!exposure.isEmpty()) logger.info("[{}] {}", camera.getName(), exposure);
			if (cameraErrorView.isPresent()) cameraErrorView.get().showFPSWarning(camera, getFPS());
			showedFPSWarning = true;
		}
	}

	private DiagnosticMessage brightnessDiagnosticWarning = null;

	public void showBrightnessWarning() {
		if (!TimerPool.isWaiting(brightnessDiagnosticFuture)) {
			brightnessDiagnosticWarning = cameraView.addDiagnosticWarning("Warning: Excessive brightness");
		} else {
			// Stop the existing timer and start a new one
			TimerPool.cancelTimer(brightnessDiagnosticFuture);
		}
		brightnessDiagnosticFuture = TimerPool.schedule(() -> {
			if (brightnessDiagnosticWarning != null) {
				brightnessDiagnosticWarning.remove();
				brightnessDiagnosticWarning = null;
			}
		}, DIAGNOSTIC_MESSAGE_DURATION);

		if (!shownBrightnessWarning) {
			shownBrightnessWarning = true;
			if (cameraErrorView.isPresent()) cameraErrorView.get().showBrightnessWarning(camera);
		}
	}

	private DiagnosticMessage motionDiagnosticWarning = null;

	public void showMotionWarning() {
		if (!TimerPool.isWaiting(motionDiagnosticFuture)) {
			motionDiagnosticWarning = cameraView
					.addDiagnosticWarning("Warning: Excessive motion -- Try reducing the camera exposure setting");
		} else {
			// Stop the existing timer and start a new one
			TimerPool.cancelTimer(motionDiagnosticFuture);
		}
		motionDiagnosticFuture = TimerPool.schedule(() -> {
			if (motionDiagnosticWarning != null) {
				motionDiagnosticWarning.remove();
				motionDiagnosticWarning = null;
			}
		}, DIAGNOSTIC_MESSAGE_DURATION);
	}

	private void fireAutoCalibration() {
		acm.reset();
	}

	protected void autoCalibrateSuccess(Rect arenaBounds, Optional<Size> paperDims, long delay) {
		// Under the camera's monitor, as disableAutoCalibration is: a Cancel either ends auto-calibration first, and
		// this does nothing, or waits until the camera is marked calibrated, and then puts the old calibration back
		// over it (spec §8 Revision 5, decision 2)
		synchronized (camera) {
			if (!isAutoCalibrating.get() || cameraCalibrationListener == null) return;
			isAutoCalibrating.set(false);
			cameraAutoCalibrated = true;
		}

		logger.debug("autoCalibrateSuccess {} {} {} {} paper {}", (int) arenaBounds.getMinX(),
				(int) arenaBounds.getMinY(), (int) arenaBounds.getWidth(), (int) arenaBounds.getHeight(),
				paperDims.isPresent());

		cameraCalibrationListener.calibrate(arenaBounds, paperDims, false, delay);

		if (recordCalibratedArea && !recordingCalibratedArea)
			startRecordingCalibratedArea(new File("calibratedArea.mp4"), (int) arenaBounds.getWidth(),
					(int) arenaBounds.getHeight());
	}

	public void enableAutoCalibration(boolean calculateFrameDelay) {
		// A fresh one: the last one's warp stays whole in whatever saveCalibration saved, and a frame it is still
		// processing can't change a warp restoreCalibration put back
		acm = new AutoCalibrationManager(this, camera, calculateFrameDelay);
		isAutoCalibrating.set(true);
		cameraAutoCalibrated = false;

		// Held from the start of the search, not from its first look, which in a dark scene waits for a frame up to
		// 2 s away (spec §8 Revision 5, decision 1). And before the exposure step's probe (fireAutoCalibration),
		// which switches the exposure to manual and back
		camera.limitExposureToFramePeriod();

		fireAutoCalibration();
	}

	public void disableAutoCalibration() {
		// Under the camera's monitor, so an exposure call the calibration has under way ends first, and it makes no
		// more: Cancel puts the old exposure back (restoreCalibration) only after this (spec §8 Revision 5, decision 2)
		synchronized (camera) {
			isAutoCalibrating.set(false);
			final AutoCalibrationManager acm = this.acm;
			if (acm != null) acm.stop();
			// Whatever ended it (the pattern found, Cancel, the time limit), the search's frame-rate limit ends too
			camera.releaseExposureLimit();
		}
	}

	@Override
	public boolean isPatternFound() {
		final AutoCalibrationManager acm = this.acm;
		return isAutoCalibrating.get() && acm != null && acm.patternFound();
	}

	// What saveCalibration saves: the auto-calibration manager holds the perspective warp and the paper size
	private record SavedCalibration(Optional<Rect> projectionBounds, boolean autoCalibrated, AutoCalibrationManager acm,
			OptionalDouble manualExposure) implements CalibrationCamera.Saved {}

	@Override
	public CalibrationCamera.Saved saveCalibration() {
		return new SavedCalibration(projectionBounds, cameraAutoCalibrated, acm, camera.manualExposure());
	}

	@Override
	public void restoreCalibration(CalibrationCamera.Saved saved) {
		final SavedCalibration calibration = (SavedCalibration) saved;
		// The warp before the flag, so a frame that sees the flag finds the warp
		acm = calibration.acm();
		cameraAutoCalibrated = calibration.autoCalibrated();
		setProjectionBounds(calibration.projectionBounds().orElse(null));

		if (calibration.manualExposure().isPresent()) {
			camera.restoreManualExposure(calibration.manualExposure().getAsDouble());
		} else {
			camera.resetExposure();
		}
	}

	@Override
	public void setArenaBackground(String resourceFilename) {
		if (cameraCalibrationListener == null) {
			logger.error("setArenaBackground called when controller is null");
			return;
		}

		cameraCalibrationListener.setArenaBackground(resourceFilename);
	}

	/**
	 * Opens the camera driver's own settings dialog, if it has one. The PS3 Eye's settings window
	 * is part of the UI (com.shootoff.gui.PS3EyeSettingsWindow).
	 */
	public void launchCameraSettings() {
		if (camera instanceof SarxosCaptureCamera) {
			((SarxosCaptureCamera) camera).launchCameraSettings();
		}
	}

	public double getFPS() {
		return camera.getFPS();
	}

	public int getFrameCount() {
		return camera.getFrameCount();
	}

	public long cameraTimeToShotTime(long timestamp) {
		if (startTime == 0) {
			resetStartTime(timestamp);
			return 0;
		}

		return timestamp - startTime;
	}

	@Override
	public void newFPS(double cameraFPS) {
		if (debuggerListener.isPresent()) debuggerListener.get().updateFeedData(cameraFPS);

		checkIfMinimumFPS(cameraFPS);
	}

	@Override
	public void cameraClosed() {
		setCameraState(CameraState.CLOSED);
		close();
	}

	@Override
	public void calibrate(Rect arenaBounds, Optional<Size> perspectivePaperDims, boolean calibratedFromCanvas,
			long delay) {
		autoCalibrateSuccess(arenaBounds, perspectivePaperDims, delay);
	}

	public Point undistortCoords(int x, int y) {
		final AutoCalibrationManager acm = this.acm;
		if (acm == null) return new Point(x, y);
		return acm.undistortCoords(x, y);
	}

	public boolean isCalibrated() {
		return projectionBounds.isPresent();
	}

}
