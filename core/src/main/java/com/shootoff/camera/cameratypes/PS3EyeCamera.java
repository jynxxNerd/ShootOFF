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

/*
 * eyeCam32.dll and eyeCam64.dll were compiled by ifly53e using the source from
 * https:\\github.com\inspirit\PS3EYEDriver
 *
 *Follow the instructions to install the proper PS3Eye usb driver from this link:
 *https:\\github.com\cboulay\psmove-ue4\wiki\Windows-PSEye-Setup
 *
 *You will need a program called Zadig to help install the usb driver:
 *http:\\zadig.akeo.ie\downloads\zadig_2.2.exe
 *
 *Missing from the Zadig instructions is to click on the Options menu and
 *click "List All Devices" so that you can see the PS3Eye camera in the first place.
 *
 *Test your setup with ps3eye_sdl.exe found at:
 *https:\\github.com\cboulay\psmove-ue4\tree\master\Binaries\Win64
 *
 *Start ShootOFF and it should see the PS3Eye.
 *
 *You can right click on the feed to bring up the configure camera menu
 *and adjust the gain and exposure.  You can also toggle "auto gain"
 *on and off and see the current FPS there.
 */

package com.shootoff.camera.cameratypes;

import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.util.Optional;

import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.shootoff.camera.CameraFactory;
import com.shootoff.camera.CameraManager;
import com.shootoff.camera.CameraView;
import com.shootoff.camera.Frame;
import com.shootoff.camera.shotdetection.JavaShotDetector;
import com.shootoff.camera.shotdetection.NativeShotDetector;
import com.shootoff.camera.shotdetection.ShotDetector;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.PointerType;

public class PS3EyeCamera extends CalculatedFPSCamera {
	private static final Logger logger = LoggerFactory.getLogger(PS3EyeCamera.class);

	private static boolean initialized = false;
	private Dimension dimension = null;

	private static final int VIEW_WIDTH = 640;
	private static final int VIEW_HEIGHT = 480;

	private static eyecam.ps3eye_t ps3ID = null;
	private static boolean closed = true;

	private Optional<Integer> origExposure = Optional.empty();

	private static eyecam eyecamLib;
	private static byte[] ba = new byte[getViewWidth() * getViewHeight() * 4];

	public PS3EyeCamera() {
		if (!initialized) {
			init();
		}
	}

	/**
	 * Receives updates for an open settings window.
	 */
	public interface SettingsListener {
		void fpsUpdated(double fps);

		void cameraClosing();
	}

	private volatile Optional<SettingsListener> settingsListener = Optional.empty();

	/**
	 * @param listener
	 *            the open settings window, or <tt>null</tt> when it closes
	 */
	public void setSettingsListener(SettingsListener listener) {
		settingsListener = Optional.ofNullable(listener);
	}

	public int getGain() {
		return eyecamLib.ps3eye_get_parameter(ps3ID, eyecam.ps3eye_parameter.PS3EYE_GAIN);
	}

	public void setGain(int gain) {
		eyecamLib.ps3eye_set_parameter(ps3ID, eyecam.ps3eye_parameter.PS3EYE_GAIN, gain);
	}

	public boolean isAutoGain() {
		return eyecamLib.ps3eye_get_parameter(ps3ID, eyecam.ps3eye_parameter.PS3EYE_AUTO_GAIN) != 0;
	}

	public void setAutoGain(boolean autoGain) {
		eyecamLib.ps3eye_set_parameter(ps3ID, eyecam.ps3eye_parameter.PS3EYE_AUTO_GAIN, autoGain ? 1 : 0);
	}

	public static void init() {
		if (initialized) return;

		try {
			final String architecture = System.getProperty("sun.arch.data.model");

			if (logger.isDebugEnabled()) logger.debug("OS type is: {}", architecture);

			if (architecture != null && "64".equals(architecture)) {
				logger.trace("Trying to load eyeCam64.dll");
				eyecamLib = (eyecam) Native.loadLibrary("eyeCam64", eyecam.class);
				logger.trace("Successfully loaded eyeCam64.dll");
			} else if (architecture != null && "32".equals(architecture)) {
				logger.trace("Trying to load eyeCam32.dll");
				eyecamLib = (eyecam) Native.loadLibrary("eyeCam32", eyecam.class);
				logger.trace("Successfully loaded eyeCam32.dll");
			}
		} catch (final UnsatisfiedLinkError exception) {
			logger.error("PS3EyeCamera eyecam ULE, Can't find the eyeCamXX.dll or "
					+ "the Visual Studio Visual C++ runtime files are not installed: ", exception);
			initialized = false;
			return;
		}

		if (eyecamLib == null) {
			logger.info("Architecture not accounted for, PS3Eye not loaded");
			initialized = false;
			return;
		}

		eyecamLib.ps3eye_init();

		if (eyecamLib.ps3eye_count_connected() >= 1) {
			logger.trace("Found a PS3EYE camera, setting up communications with it");

			if (openPS3Eye()) {
				logger.trace("Communications with PS3Eye camera established");
				initialized = true;
			} else {
				logger.trace("Communications with PS3Eye camera NOT established");
				initialized = false;
			}

		} else {
			initialized = false;
		}

		if (initialized()) {
			if (eyecamLib.ps3eye_set_parameter(ps3ID, eyecam.ps3eye_parameter.PS3EYE_GAIN, 16) == -1) {
				logger.error("Error setting gain on PS3Eye during initialization, "
						+ "shutdown ShootOFF and unplug and re-plug in the PS3Eye to the usb port");
			}
			if (eyecamLib.ps3eye_set_parameter(ps3ID, eyecam.ps3eye_parameter.PS3EYE_EXPOSURE, 50) == -1) {
				logger.error("Error setting exposure on PS3Eye during initialization, "
						+ "shutdown ShootOFF and unplug and re-plug in the PS3Eye to the usb port");
			}

			CameraFactory.registerCamera(new PS3EyeCamera());
			logger.trace("PS3Eye adjusted and registered");
		}

	}// end init

	private static boolean openPS3Eye() {
		ps3ID = eyecamLib.ps3eye_open(0, getViewWidth(), getViewHeight(), 75, eyecam.ps3eye_format.PS3EYE_FORMAT_BGR);

		closed = ps3ID == null;

		return ps3ID != null;
	}

	@Override
	public String getName() {
		return "PS3Eye";
	}

	private static int getViewWidth() {
		return VIEW_WIDTH;
	}

	private static int getViewHeight() {
		return VIEW_HEIGHT;
	}

	private static void closeMe() {
		if (closed) return;

		eyecamLib.ps3eye_close(ps3ID);
		eyecamLib.ps3eye_uninit();
		logger.debug("PS3Eye camera closed");
		ps3ID = null;
		closed = true;
	}

	@Override
	public synchronized void close() {
		final Optional<SettingsListener> listener = settingsListener;
		settingsListener = Optional.empty();
		if (listener.isPresent()) listener.get().cameraClosing();

		closeMe();
	}

	@Override
	public boolean isOpen() {
		if (ps3ID != null) {
			closed = false;
			return true;
		} else {
			closed = true;
			return false;
		}
	}

	@Override
	public void setViewSize(final Dimension size) {
		return;
	}

	@Override
	public Dimension getViewSize() {
		if (dimension != null) return dimension;

		dimension = new Dimension(getViewWidth(), getViewHeight());

		return dimension;
	}

	private byte[] getImageNative() {
		eyecamLib.ps3eye_grab_frame(ps3ID, ba);

		return ba;
	}

	public Mat translateCameraArrayToMat(byte[] imageBuffer) {
		final Mat mat = new Mat(getViewHeight(), getViewWidth(), CvType.CV_8UC3);

		mat.put(0, 0, imageBuffer);
		return mat;
	}

	@Override
	public synchronized boolean open() {
		if (!closed) return true;
		openPS3Eye();
		return isOpen();
	}

	@Override
	public Frame getFrame() {
		final byte[] frame = getImageNative();
		final long currentFrameTimestamp = System.currentTimeMillis();
		final Mat mat = translateCameraArrayToMat(frame);
		frameCount++;
		return new Frame(mat, currentFrameTimestamp);
	}

	@Override
	public BufferedImage getBufferedImage() {
		return getFrame().getOriginalBufferedImage();
	}

	@Override
	public ShotDetector getPreferredShotDetector(final CameraManager cameraManager, final CameraView cameraView) {
		if (NativeShotDetector.isSystemSupported()) {
			return new NativeShotDetector(cameraManager, cameraView);
		} else if (JavaShotDetector.isSystemSupported()) {
			logger.trace("starting javaShotDetector for PS3Eye");
			return new JavaShotDetector(cameraManager, cameraView);
		} else
			return null;
	}

	public static boolean initialized() {
		return initialized;
	}

	@Override
	public void run() {
		while (isOpen()) {
			try {
				if (cameraEventListener.isPresent()) cameraEventListener.get().newFrame(getFrame());
			} catch (Exception e) {
				// Normally we wouldn't catch such a generic exception,
				// but OpenCV throws a generic exception with no information
				// when confronted with a subtly invalid frame. The PS3eye
				// occasionally produces such frames.
				logger.warn("Invalid frame from PS3eye could not be converted to OpenCV mat. Skipping frame.");
			}

			if (((int) (getFrameCount() % Math.min(getFPS(), 5)) == 0) && cameraState != CameraState.CALIBRATING) {
				estimateCameraFPS();
			}

			final Optional<SettingsListener> listener = settingsListener;
			if (listener.isPresent()) listener.get().fpsUpdated(getFPS());
		}

		if (cameraEventListener.isPresent()) cameraEventListener.get().cameraClosed();
	}

	@Override
	public boolean isLocked() {
		return false;
	}

	public int getExposure() {
		return eyecamLib.ps3eye_get_parameter(ps3ID, eyecam.ps3eye_parameter.PS3EYE_EXPOSURE);
	}

	public void setExposure(int exposure) {
		eyecamLib.ps3eye_set_parameter(ps3ID, eyecam.ps3eye_parameter.PS3EYE_EXPOSURE, exposure);
	}

	@Override
	public boolean supportsExposureAdjustment() {
		if (!origExposure.isPresent()) origExposure = Optional.of(getExposure());
		return true;
	}

	@Override
	public boolean decreaseExposure() {
		final int curExp = getExposure();
		final int newExp = (int) (curExp - (.1 * curExp));
		logger.trace("curExp[ {} newExp {}", curExp, newExp);

		if (newExp < 17) return false;

		setExposure(newExp);
		logger.trace("curExp[ {} newExp {} res {}", curExp, newExp, getExposure());
		return (getExposure() == newExp);
	}

	@Override
	public void resetExposure() {
		if (origExposure.isPresent()) setExposure(origExposure.get());
	}

	@Override
	public boolean limitsFrames() {
		return false;
	}

	public interface eyecam extends Library {
		public static class ps3eye_t extends PointerType {
			public ps3eye_t() {}

			protected ps3eye_t(Pointer ps3eye_t) {
				super(ps3eye_t);
			}
		}

		public static interface ps3eye_parameter {
			public static final int PS3EYE_AUTO_GAIN = 0; // [false, true]
			public static final int PS3EYE_GAIN = 1; // [0, 63]
			public static final int PS3EYE_AUTO_WHITEBALANCE = 2; // [false,
			// true]
			public static final int PS3EYE_EXPOSURE = 3; // [0, 255]
			public static final int PS3EYE_SHARPNESS = 4; // [0 63]
			public static final int PS3EYE_CONTRAST = 5; // [0, 255]
			public static final int PS3EYE_BRIGHTNESS = 6; // [0, 255]
			public static final int PS3EYE_HUE = 7; // [0, 255]
			public static final int PS3EYE_REDBALANCE = 8; // [0, 255]
			public static final int PS3EYE_BLUEBALANCE = 9; // [0, 255]
			public static final int PS3EYE_GREENBALANCE = 10; // [0, 255]
			public static final int PS3EYE_HFLIP = 11; // [false, true]
			public static final int PS3EYE_VFLIP = 12; // [false, true]
		};

		public static interface ps3eye_format {
			public static final int PS3EYE_FORMAT_BAYER = 0;
			public static final int PS3EYE_FORMAT_BGR = 1;
			public static final int PS3EYE_FORMAT_RGB = 2;
		}

		/**
		 * Initialize and enumerate connected cameras. Needs to be called once
		 * before all other API functions.
		 **/
		void ps3eye_init();

		/**
		 * De-initialize the library and free resources. If a pseye_t * object
		 * is still opened, nothing happens.
		 **/
		void ps3eye_uninit();

		/**
		 * Return the number of PSEye cameras connected via USB.
		 **/
		int ps3eye_count_connected();

		/**
		 * Open a PSEye camera device by id. The id is zero-based, and must be
		 * smaller than the count. width and height should usually be 640x480 or
		 * 320x240 fps is the target frame rate, 60 usually works fine here
		 **/
		ps3eye_t ps3eye_open(int id, int width, int height, int fps, int outputFormat);

		/**
		 * Get the string that uniquely identifies this camera Returns 0 on
		 * success, -1 on failure
		 **/
		int ps3eye_get_unique_identifier(ps3eye_t eye, char[] out_identifier, int max_identifier_length);

		/**
		 * Grab the next frame as YUV422 blob. YUV422 4 bytes per 2 pixels ( 8
		 * bytes per 4 pixels) A pointer to the buffer will be passed back. The
		 * buffer will only be valid until the next call, or until the eye is
		 * closed again with ps3eye_close(). If stride is not NULL, the byte
		 * offset between two consecutive lines in the frame will be written to
		 * *stride.
		 **/
		void ps3eye_grab_frame(ps3eye_t eye, byte[] ba);

		/**
		 * Close a PSEye camera device and free allocated resources. To really
		 * close the library, you should also call ps3eye_uninit().
		 **/
		void ps3eye_close(ps3eye_t eye);

		/**
		 * Set a ps3eye_parameter to a value. Returns -1 if there is an error,
		 * otherwise 0.
		 **/
		int ps3eye_set_parameter(ps3eye_t eye, int param, int value);

		/**
		 * Get a ps3eye_parameter value. Returns -1 if there is an error,
		 * otherwise returns the parameter value int.
		 **/
		int ps3eye_get_parameter(ps3eye_t eye, int ps3eyeGain);

	}// end eyecam interface
}