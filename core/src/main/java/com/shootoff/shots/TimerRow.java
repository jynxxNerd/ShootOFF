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

import java.util.Optional;

import com.shootoff.camera.Shot;
import com.shootoff.camera.shot.ShotColor;

/**
 * What a shot timer row shows, as text: the shot's time and split in seconds and its laser.
 *
 * @param time
 *            the shot's time, e.g. "2.50"
 * @param split
 *            the time since the previous row's shot, e.g. "1.50", or "-" for the first row
 * @param laser
 *            "red", "green" or "infrared"
 * @param hadMalfunction
 *            <tt>true</tt> if a malfunction rejected the shot before this one
 * @param hadReload
 *            <tt>true</tt> if the virtual magazine rejected the shot before this one (a reload)
 */
public record TimerRow(Shot shot, String time, String split, String laser, boolean hadMalfunction,
		boolean hadReload) {
	/**
	 * @param previous
	 *            the shot of the row before this one, if any
	 */
	public static TimerRow of(Shot shot, Optional<Shot> previous, boolean hadMalfunction, boolean hadReload) {
		final String laser;
		if (ShotColor.RED.equals(shot.getColor())) {
			laser = "red";
		} else if (ShotColor.GREEN.equals(shot.getColor())) {
			laser = "green";
		} else {
			laser = "infrared";
		}

		final float timestampS = ((float) shot.getTimestamp()) / 1000f;
		final String time = String.format("%.2f", timestampS);

		final String split;
		if (previous.isPresent()) {
			split = String.format("%.2f", timestampS - ((float) previous.get().getTimestamp() / 1000f));
		} else {
			split = "-";
		}

		return new TimerRow(shot, time, split, laser, hadMalfunction, hadReload);
	}
}
