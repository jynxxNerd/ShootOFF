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

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Objects;

/**
 * The transparency of an image region's current frame, which the UI gives the hit tester (see
 * {@link TargetSet#setImageMask}). A shot on a fully transparent pixel of an image misses it.
 */
public final class AlphaMask {
	private final BufferedImage image;
	private volatile AlphaMask lastScaled;

	private AlphaMask(BufferedImage image) {
		this.image = image;
	}

	public static AlphaMask of(BufferedImage image) {
		return new AlphaMask(Objects.requireNonNull(image, "image"));
	}

	public int getWidth() {
		return image.getWidth();
	}

	public int getHeight() {
		return image.getHeight();
	}

	/**
	 * @return <tt>true</tt> unless the pixel is fully transparent
	 */
	public boolean isOpaque(int x, int y) {
		return (image.getRGB(x, y) >> 24) != 0;
	}

	/**
	 * @return the mask scaled to the given size exactly as the JavaFX app always scaled a resized
	 *         target's image before testing a hit: java.awt smooth scaling, drawn into an ARGB
	 *         image. The most recent size is cached.
	 */
	public AlphaMask scaledTo(int width, int height) {
		final AlphaMask cached = lastScaled;
		if (cached != null && cached.getWidth() == width && cached.getHeight() == height) return cached;

		final java.awt.Image scaled = image.getScaledInstance(width, height, java.awt.Image.SCALE_SMOOTH);
		final BufferedImage resized = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		final Graphics2D g2d = resized.createGraphics();
		g2d.drawImage(scaled, 0, 0, null);
		g2d.dispose();

		final AlphaMask result = new AlphaMask(resized);
		lastScaled = result;
		return result;
	}
}
