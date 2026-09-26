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

package com.shootoff.session;

import java.io.File;
import java.util.Optional;

import com.shootoff.targets.model.PlacedTarget;
import com.shootoff.targets.model.TargetId;
import com.shootoff.targets.model.TargetSet;

/**
 * How session events refer to a target: the set it is in (its canvas's targets) and its id there.
 * Events store the target's index in the set at the moment they are recorded.
 */
public record TargetRef(TargetSet targets, TargetId id) {
	/**
	 * @return the target's index in its set, or -1 if it isn't in the set
	 */
	public int index() {
		return targets.indexOf(id);
	}

	public Optional<PlacedTarget> target() {
		return targets.get(id);
	}

	/**
	 * @return the target's file; empty if the target isn't in its set or has no file (e.g. the
	 *         manual calibration rectangle)
	 */
	public Optional<File> file() {
		return target().flatMap(t -> t.getDefinition().file());
	}
}
