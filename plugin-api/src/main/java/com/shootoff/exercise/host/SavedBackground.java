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

package com.shootoff.exercise.host;

import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The arena background from before an exercise first changed it, to put back when the exercise stops.
 * Use it on the user interface's thread.
 *
 * @param <B>
 *            the user interface's background type
 */
public final class SavedBackground<B> {
	private boolean changed = false;
	private Optional<B> previous = Optional.empty();

	/**
	 * Call before each change: the first time, remembers the current background.
	 */
	public void beforeChange(Supplier<Optional<B>> current) {
		if (changed) return;

		previous = current.get();
		changed = true;
	}

	/**
	 * Puts back the background from before the first change (empty for none), if there was a change.
	 */
	public void restore(Consumer<Optional<B>> setBackground) {
		if (changed) setBackground.accept(previous);
	}
}
