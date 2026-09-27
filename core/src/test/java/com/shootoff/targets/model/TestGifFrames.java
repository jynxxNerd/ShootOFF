package com.shootoff.targets.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.FileInputStream;
import java.io.InputStream;
import java.util.List;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import org.junit.jupiter.api.Test;

import com.shootoff.targets.model.GifFrames.GifFrame;

class TestGifFrames {
	private static final String POPPER = "targets/pepper_popper.gif";

	@Test
	void everyFrameIsWholeAtTheLogicalScreenSize() throws Exception {
		final List<GifFrame> frames;
		try (InputStream in = new FileInputStream(POPPER)) {
			frames = GifFrames.read(in);
		}

		final int[] logical;
		try (InputStream in = new FileInputStream(POPPER)) {
			logical = ImageSizes.read(in, true);
		}

		assertEquals(frameCount(), frames.size());
		for (final GifFrame frame : frames) {
			assertEquals(logical[0], frame.image().getWidth());
			assertEquals(logical[1], frame.image().getHeight());
			assertTrue(frame.delayMillis() >= 0);
		}
	}

	@Test
	void framesAreComposedOverTheOnesBefore() throws Exception {
		final List<GifFrame> frames;
		try (InputStream in = new FileInputStream(POPPER)) {
			frames = GifFrames.read(in);
		}

		// The popper falls: the first and last frames differ, and each is a separate copy
		final GifFrame first = frames.get(0);
		final GifFrame last = frames.get(frames.size() - 1);
		assertNotEquals(opaquePixels(first), opaquePixels(last));
		assertTrue(first.image() != last.image());
	}

	private static int frameCount() throws Exception {
		try (ImageInputStream in = ImageIO.createImageInputStream(new FileInputStream(POPPER))) {
			final ImageReader reader = ImageIO.getImageReadersByFormatName("gif").next();
			reader.setInput(in);
			final int count = reader.getNumImages(true);
			reader.dispose();
			return count;
		}
	}

	private static int opaquePixels(GifFrame frame) {
		int count = 0;
		for (int x = 0; x < frame.image().getWidth(); x++) {
			for (int y = 0; y < frame.image().getHeight(); y++) {
				if ((frame.image().getRGB(x, y) >>> 24) != 0) count++;
			}
		}
		return count;
	}
}
