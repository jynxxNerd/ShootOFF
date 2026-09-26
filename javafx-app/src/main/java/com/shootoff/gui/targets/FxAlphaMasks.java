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

package com.shootoff.gui.targets;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import com.shootoff.targets.model.AlphaMask;

import javafx.embed.swing.SwingFXUtils;
import javafx.scene.image.Image;

/**
 * Turns the JavaFX image an image region shows into the alpha mask the core hit tester reads.
 * Animation frames repeat, so masks are cached per image.
 */
public final class FxAlphaMasks {
	private static final Map<Image, AlphaMask> MASKS = Collections.synchronizedMap(new WeakHashMap<>());

	private FxAlphaMasks() {}

	public static AlphaMask of(Image image) {
		return MASKS.computeIfAbsent(image, i -> AlphaMask.of(SwingFXUtils.fromFXImage(i, null)));
	}
}
