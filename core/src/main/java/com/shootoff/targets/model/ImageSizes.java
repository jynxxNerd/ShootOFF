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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;

import org.w3c.dom.NodeList;

/**
 * Reads an image's size as the JavaFX app shows it, without decoding its pixels.
 */
final class ImageSizes {
	private ImageSizes() {}

	/**
	 * @return <tt>true</tt> if the JavaFX app animates the image: the part of the file name after
	 *         its first "." ends with "gif" (the test TargetIO has always used)
	 */
	static boolean isGif(String path) {
		final String name = new File(path).getName();
		return name.substring(name.indexOf('.') + 1).endsWith("gif");
	}

	/**
	 * @return {width, height}: for a GIF its logical screen size (the size of every frame
	 *         GifAnimation makes), otherwise the first image's size
	 */
	static int[] read(InputStream in, boolean gif) throws IOException {
		try (ImageInputStream imageInput = ImageIO.createImageInputStream(in)) {
			if (imageInput == null) throw new IOException("no image reader input");

			final Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInput);
			if (!readers.hasNext()) throw new IOException("not an image format ShootOFF can read");

			final ImageReader reader = readers.next();
			try {
				reader.setInput(imageInput);

				if (gif) {
					final IIOMetadata metadata = reader.getStreamMetadata();
					if (metadata != null) {
						final IIOMetadataNode root = (IIOMetadataNode) metadata
								.getAsTree(metadata.getNativeMetadataFormatName());
						final NodeList descriptors = root.getElementsByTagName("LogicalScreenDescriptor");

						if (descriptors.getLength() > 0) {
							final IIOMetadataNode descriptor = (IIOMetadataNode) descriptors.item(0);
							return new int[] { Integer.parseInt(descriptor.getAttribute("logicalScreenWidth")),
									Integer.parseInt(descriptor.getAttribute("logicalScreenHeight")) };
						}
					}
				}

				return new int[] { reader.getWidth(0), reader.getHeight(0) };
			} finally {
				reader.dispose();
			}
		}
	}
}
