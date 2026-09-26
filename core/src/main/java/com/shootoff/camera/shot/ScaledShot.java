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

package com.shootoff.camera.shot;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.shootoff.camera.Shot;

/**
 * A {@link BoundsShot} that can be scaled from camera-feed to display coordinates. This is the
 * shot data shot detection hands to a camera view; each view creates its own marker for it.
 *
 * @author cbdmaul
 */
public class ScaledShot extends BoundsShot {
	private static final Logger logger = LoggerFactory.getLogger(ScaledShot.class);

	private Optional<Double> displayX = Optional.empty();
	private Optional<Double> displayY = Optional.empty();

	public ScaledShot(ShotColor color, double x, double y, long timestamp, int frame) {
		super(color, x, y, timestamp, frame);
	}

	public ScaledShot(ShotColor color, double x, double y, long timestamp) {
		super(color, x, y, timestamp);
	}

	public ScaledShot(Shot shot) {
		super(shot);

		if (shot instanceof ScaledShot) {
			displayX = ((ScaledShot) shot).displayX;
			displayY = ((ScaledShot) shot).displayY;
		}
	}

	public void setDisplayVals(int displayWidth, int displayHeight, int feedWidth, int feedHeight) {
		final double scaleX = (double) displayWidth / (double) feedWidth;
		final double scaleY = (double) displayHeight / (double) feedHeight;

		double scaledX, scaledY;
		if (displayX.isPresent()) {
			scaledX = displayX.get() * scaleX;
			scaledY = displayY.get() * scaleY;
		} else {
			scaledX = super.getX() * scaleX;
			scaledY = super.getY() * scaleY;
		}

		if (logger.isTraceEnabled()) {
			logger.trace("setTranslation {} {} - {} {} to {} {}", scaleX, scaleY, super.getX(), super.getY(), scaledX,
					scaledY);
		}

		displayX = Optional.of(scaledX);
		displayY = Optional.of(scaledY);
	}

	@Override
	public double getX() {
		if (!displayX.isPresent()) return super.getX();
		return displayX.get();
	}

	@Override
	public double getY() {
		if (!displayY.isPresent()) return super.getY();
		return displayY.get();
	}

	public double getDisplayX() {
		if (!displayX.isPresent()) return super.getX();
		return displayX.get();
	}

	public double getDisplayY() {
		if (!displayY.isPresent()) return super.getY();
		return displayY.get();
	}

	/**
	 * Hands a copy this shot's bounds-adjusted, not display-scaled position (i.e. {@link
	 * BoundsShot#getX()}), so copying a scaled shot doesn't bake the display scaling into the
	 * copy's origin.
	 */
	@Override
	protected double getCameraX() {
		return super.getX();
	}

	@Override
	protected double getCameraY() {
		return super.getY();
	}
}
