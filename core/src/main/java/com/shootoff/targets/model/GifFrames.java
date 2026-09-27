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

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;

import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Reads an animated GIF into whole frames, each drawn over the frames before it as the GIF's disposal
 * methods say, at the GIF's logical screen size. The pixels are the user interface's to draw; the hit
 * tester sees the current frame as an {@link AlphaMask}.
 */
public final class GifFrames {
	private GifFrames() {}

	/**
	 * One whole frame.
	 *
	 * @param delayMillis
	 *            how long the GIF shows it
	 * @param disposal
	 *            the frame's disposal method, e.g. "none" or "restoreToPrevious"
	 */
	public record GifFrame(BufferedImage image, int delayMillis, String disposal) {}

	// This method is from http://stackoverflow.com/a/17269591
	public static List<GifFrame> read(InputStream stream) throws IOException {
		final ArrayList<GifFrame> frames = new ArrayList<>(2);

		int width = -1;
		int height = -1;

		final ImageReader reader = ImageIO.getImageReadersByFormatName("gif").next();
		reader.setInput(ImageIO.createImageInputStream(stream));
		final IIOMetadata metadata = reader.getStreamMetadata();
		if (metadata != null) {
			final IIOMetadataNode globalRoot = (IIOMetadataNode) metadata.getAsTree(metadata.getNativeMetadataFormatName());

			final NodeList globalScreenDescriptor = globalRoot.getElementsByTagName("LogicalScreenDescriptor");

			if (globalScreenDescriptor.getLength() > 0) {
				final IIOMetadataNode screenDescriptor = (IIOMetadataNode) globalScreenDescriptor.item(0);

				if (screenDescriptor != null) {
					width = Integer.parseInt(screenDescriptor.getAttribute("logicalScreenWidth"));
					height = Integer.parseInt(screenDescriptor.getAttribute("logicalScreenHeight"));
				}
			}
		}

		BufferedImage master = null;
		Graphics2D masterGraphics = null;

		for (int frameIndex = 0;; frameIndex++) {
			BufferedImage image;
			try {
				image = reader.read(frameIndex);
			} catch (final IndexOutOfBoundsException io) {
				break;
			}

			if (width == -1 || height == -1) {
				width = image.getWidth();
				height = image.getHeight();
			}

			final IIOMetadataNode root = (IIOMetadataNode) reader.getImageMetadata(frameIndex)
					.getAsTree("javax_imageio_gif_image_1.0");
			final IIOMetadataNode gce = (IIOMetadataNode) root.getElementsByTagName("GraphicControlExtension").item(0);
			final int delay = Integer.parseInt(gce.getAttribute("delayTime")) * 10;
			final String disposal = gce.getAttribute("disposalMethod");

			int x = 0;
			int y = 0;

			if (master == null) {
				master = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
				masterGraphics = master.createGraphics();
				masterGraphics.setBackground(new Color(0, 0, 0, 0));
			} else {
				final NodeList children = root.getChildNodes();
				for (int nodeIndex = 0; nodeIndex < children.getLength(); nodeIndex++) {
					final Node nodeItem = children.item(nodeIndex);
					if (nodeItem.getNodeName().equals("ImageDescriptor")) {
						final NamedNodeMap map = nodeItem.getAttributes();
						x = Integer.parseInt(map.getNamedItem("imageLeftPosition").getNodeValue());
						y = Integer.parseInt(map.getNamedItem("imageTopPosition").getNodeValue());
					}
				}
			}
			masterGraphics.drawImage(image, x, y, null);

			final BufferedImage copy = new BufferedImage(master.getColorModel(), master.copyData(null),
					master.isAlphaPremultiplied(), null);
			frames.add(new GifFrame(copy, delay, disposal));

			if (disposal.equals("restoreToPrevious")) {
				BufferedImage from = null;
				for (int i = frameIndex - 1; i >= 0; i--) {
					if (!frames.get(i).disposal().equals("restoreToPrevious") || frameIndex == 0) {
						from = frames.get(i).image();
						break;
					}
				}

				master = new BufferedImage(from.getColorModel(), from.copyData(null), from.isAlphaPremultiplied(),
						null);
				masterGraphics = master.createGraphics();
				masterGraphics.setBackground(new Color(0, 0, 0, 0));
			} else if (disposal.equals("restoreToBackgroundColor")) {
				masterGraphics.clearRect(x, y, image.getWidth(), image.getHeight());
			}
		}
		reader.dispose();

		return frames;
	}
}
