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

package com.shootoff.targets.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * One command from a region's <tt>command</tt> tag, e.g. <tt>animate(pepper_popper)</tt>. Each UI
 * carries commands out itself (animations, sounds, reset, POI adjustment).
 */
public record RegionCommand(String name, List<String> args) {
	public RegionCommand {
		args = List.copyOf(args);
	}

	/**
	 * Parses a command tag the way the JavaFX app always has: commands are separated by ";",
	 * arguments by ",", and "name()" has one empty argument. A missing ")" ends the arguments at
	 * the end of the command.
	 *
	 * @param commandTag
	 *            the tag's value, or <tt>null</tt> for no commands
	 */
	public static List<RegionCommand> parse(String commandTag) {
		if (commandTag == null) return List.of();

		final List<RegionCommand> commands = new ArrayList<>();
		for (final String command : commandTag.split(";")) {
			final int openParen = command.indexOf('(');

			if (openParen > 0) {
				int closeParen = command.indexOf(')', openParen);
				if (closeParen < 0) closeParen = command.length();

				commands.add(new RegionCommand(command.substring(0, openParen),
						Arrays.asList(command.substring(openParen + 1, closeParen).split(","))));
			} else {
				commands.add(new RegionCommand(command, List.of()));
			}
		}

		return commands;
	}
}
