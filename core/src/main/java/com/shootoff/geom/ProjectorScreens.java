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

package com.shootoff.geom;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Which screen the projector arena goes on, whatever user interface draws it:
 * <ol>
 * <li>where the user last put the arena by hand (the saved arena position), if that is on a screen
 * other than the one the arena window opened on</li>
 * <li>otherwise, with exactly two screens, the one ShootOFF's main window isn't on</li>
 * <li>otherwise, with more than two screens, the smallest (the first of equals)</li>
 * </ol>
 * Screens are in the user interface's screen order and coordinates.
 */
public final class ProjectorScreens {
	private ProjectorScreens() {}

	public enum Reason {
		SAVED_POSITION, OTHER_OF_TWO, SMALLEST
	}

	/**
	 * @param screen
	 *            the index of the chosen screen
	 */
	public record Choice(int screen, Reason reason) {}

	/**
	 * @param arenaScreen
	 *            the screen the arena window is on now
	 * @param appScreen
	 *            the screen ShootOFF's main window is on, if known (only needed with two screens)
	 * @param savedArenaPosition
	 *            where the user last put the arena by hand, if anywhere
	 * @return the screen for the arena, or empty if none looks like a projector
	 */
	public static Optional<Choice> choose(List<Rect> screens, int arenaScreen, OptionalInt appScreen,
			Optional<Point> savedArenaPosition) {
		if (savedArenaPosition.isPresent()) {
			final Point saved = savedArenaPosition.get();

			int first = -1;
			boolean onArenaScreen = false;
			for (int i = 0; i < screens.size(); i++) {
				if (!touches(screens.get(i), saved)) continue;
				if (first < 0) first = i;
				if (i == arenaScreen) onArenaScreen = true;
			}

			if (first >= 0 && !onArenaScreen) return Optional.of(new Choice(first, Reason.SAVED_POSITION));
		}

		if (screens.size() == 2) {
			if (appScreen.isEmpty()) return Optional.empty();

			return Optional.of(new Choice(appScreen.getAsInt() == 0 ? 1 : 0, Reason.OTHER_OF_TWO));
		}

		if (screens.size() > 2) {
			int smallest = 0;
			for (int i = 1; i < screens.size(); i++) {
				if (area(screens.get(i)) < area(screens.get(smallest))) smallest = i;
			}
			return Optional.of(new Choice(smallest, Reason.SMALLEST));
		}

		return Optional.empty();
	}

	// Whether a one-pixel square at the point overlaps the screen, as JavaFX's
	// Screen.getScreensForRectangle(x, y, 1, 1) decides it
	private static boolean touches(Rect screen, Point point) {
		return point.getX() + 1 > screen.getMinX() && point.getY() + 1 > screen.getMinY()
				&& point.getX() < screen.getMaxX() && point.getY() < screen.getMaxY();
	}

	private static double area(Rect screen) {
		return screen.getHeight() * screen.getWidth();
	}
}
