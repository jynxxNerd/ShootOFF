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

package com.shootoff.config;

import java.util.Objects;
import java.util.Optional;

import com.shootoff.geom.Rect;
import com.shootoff.geom.Size;

/**
 * A projector calibration the Compose app keeps between sessions ("Remember calibration"): where the
 * projection was on the camera's feed, and the camera and projector screen it was made with. The JavaFX
 * app carries it through its saves and never uses it.
 *
 * @param camera
 *            the calibrating camera's name
 * @param feed
 *            the camera's feed size when it was calibrated
 * @param screen
 *            the projector screen's size, which is the arena's size when full screen
 * @param bounds
 *            the projection on the camera's feed, in feed pixels
 * @param paper
 *            the perspective paper's size, if auto-calibration found one
 */
public record SavedCalibration(String camera, Size feed, Size screen, Rect bounds, Optional<Size> paper) {
	public SavedCalibration {
		Objects.requireNonNull(camera, "camera");
		Objects.requireNonNull(feed, "feed");
		Objects.requireNonNull(screen, "screen");
		Objects.requireNonNull(bounds, "bounds");
		Objects.requireNonNull(paper, "paper");
	}
}
