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

package com.shootoff.camera.autocalibration;

import java.awt.image.BufferedImage;
import java.util.Optional;

import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint2f;
import org.opencv.imgproc.Imgproc;

import com.shootoff.calibration.CalibrationCheck;
import com.shootoff.camera.CameraCalibrationListener;
import com.shootoff.camera.cameratypes.Camera;
import com.shootoff.geom.Rect;
import com.shootoff.geom.Size;

/**
 * Finds the calibration pattern's bounds in one camera frame, the way auto-calibration's first step
 * does (grayscale, equalized, the chessboard, then the pattern's corners). It has its own
 * {@link AutoCalibrationManager}, so it never touches the camera's own calibration.
 */
public final class PatternDetector implements CalibrationCheck.Detector<BufferedImage> {
	// Detection only: this manager never finishes a calibration, so nothing is ever reported
	private static final CameraCalibrationListener NO_LISTENER = new CameraCalibrationListener() {
		@Override
		public void calibrate(Rect arenaBounds, Optional<Size> perspectivePaperDims, boolean calibratedFromCanvas,
				long frameDelay) {}

		@Override
		public void setArenaBackground(String resourceFilename) {}
	};

	private final AutoCalibrationManager acm;

	/**
	 * @param camera
	 *            the camera the frames come from (for its feed size)
	 */
	public PatternDetector(Camera camera) {
		acm = new AutoCalibrationManager(NO_LISTENER, camera, false);
	}

	@Override
	public synchronized Optional<Rect> detect(BufferedImage frame) {
		final Mat gray = acm.preProcessFrame(Camera.bufferedImageToMat(frame));
		Imgproc.equalizeHist(gray, gray);

		final Optional<MatOfPoint2f> board = acm.findChessboard(gray);
		if (board.isEmpty()) return Optional.empty();

		return acm.calibrateFrame(board.get(), gray);
	}
}
