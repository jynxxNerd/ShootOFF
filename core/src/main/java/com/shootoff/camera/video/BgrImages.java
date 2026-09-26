package com.shootoff.camera.video;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

public final class BgrImages {
	private BgrImages() {}

	/**
	 * @return image itself if it is a standalone TYPE_3BYTE_BGR image, otherwise
	 *         a TYPE_3BYTE_BGR copy. Subimages are copied because their raster
	 *         shares the parent's pixel buffer.
	 */
	public static BufferedImage toBgr(BufferedImage image) {
		if (image.getType() == BufferedImage.TYPE_3BYTE_BGR && image.getRaster().getParent() == null) return image;

		return copy(image);
	}

	/**
	 * @return a TYPE_3BYTE_BGR copy of image that shares no pixel data with it
	 */
	public static BufferedImage copy(BufferedImage image) {
		final BufferedImage copy = new BufferedImage(image.getWidth(), image.getHeight(),
				BufferedImage.TYPE_3BYTE_BGR);
		final Graphics2D g = copy.createGraphics();
		try {
			g.drawImage(image, 0, 0, null);
		} finally {
			g.dispose();
		}
		return copy;
	}
}
