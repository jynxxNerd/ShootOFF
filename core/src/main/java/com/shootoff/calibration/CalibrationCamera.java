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

package com.shootoff.calibration;

import java.util.Optional;

import com.shootoff.geom.Rect;

/**
 * The camera pointed at the projector arena, as calibration uses it. {@link
 * com.shootoff.camera.CameraManager} is one.
 */
public interface CalibrationCamera {
	String getName();

	int getFeedWidth();

	int getFeedHeight();

	/**
	 * @return the arena's calibrated projection on the camera feed, if it is calibrated
	 */
	Optional<Rect> getProjectionBounds();

	/**
	 * @param projectionBounds
	 *            the arena's projection on the camera feed, or <tt>null</tt> for none
	 */
	void setProjectionBounds(Rect projectionBounds);

	/**
	 * While calibrating, the camera doesn't detect shots.
	 */
	void setCalibrating(boolean isCalibrating);

	void setDetecting(boolean isDetecting);

	/**
	 * Starts looking for the calibration pattern in the camera's frames. When it finds it, the
	 * camera calls {@link CalibrationFlow#calibrated} with the pattern's bounds on the feed.
	 */
	void enableAutoCalibration(boolean calculateFrameDelay);

	void disableAutoCalibration();

	void setCropFeedToProjection(boolean cropFeed);

	void setLimitDetectProjection(boolean limitDetection);
}
