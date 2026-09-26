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

package com.shootoff.shots;

import com.shootoff.config.Settings;
import com.shootoff.geom.Point;
import com.shootoff.sound.SoundPlayer;

/**
 * The <tt>poi_adjust</tt> region command: five hits on the POI adjustment target's regions set the
 * point-of-impact offset every later shot is moved by, and a sixth turns it off. Each UI measures the
 * hit region's center on the camera feed; this does the rest.
 */
public final class PoiAdjustment {
	private PoiAdjustment() {}

	/**
	 * @param regionCenter
	 *            the center of the hit region, on the camera feed
	 * @param shot
	 *            where the shot was, on the camera feed (its bounds position)
	 * @param targetScaleX
	 *            the target's horizontal scale
	 * @return how far the shot landed from the region's center, in the target's own scale
	 */
	public static Point offset(Point regionCenter, Point shot, double targetScaleX, double targetScaleY) {
		return new Point((shot.getX() - regionCenter.getX()) / targetScaleX,
				(shot.getY() - regionCenter.getY()) / targetScaleY);
	}

	/**
	 * Counts one hit towards the adjustment and plays its sounds: <tt>beep2.wav</tt> twice when this
	 * hit turns the adjustment off, <tt>beep.wav</tt> once the adjustment is on, and <tt>beep2.wav</tt>
	 * while it is still being measured.
	 */
	public static void apply(Settings settings, Point offset) {
		if (settings.updatePOIAdjustment(offset.getX(), offset.getY())) {
			SoundPlayer.play("sounds/beep2.wav");
			try {
				Thread.sleep(200);
			} catch (final InterruptedException e) {}
			SoundPlayer.play("sounds/beep2.wav");
		} else if (settings.isAdjustingPOI())
			SoundPlayer.play("sounds/beep.wav");
		else
			SoundPlayer.play("sounds/beep2.wav");
	}
}
