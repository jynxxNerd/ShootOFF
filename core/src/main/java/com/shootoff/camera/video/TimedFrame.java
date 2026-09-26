package com.shootoff.camera.video;

import java.awt.image.BufferedImage;

public final class TimedFrame {
	private final BufferedImage image;
	private final long timestampMicros;

	public TimedFrame(BufferedImage image, long timestampMicros) {
		this.image = image;
		this.timestampMicros = timestampMicros;
	}

	public BufferedImage getImage() {
		return image;
	}

	public long getTimestampMicros() {
		return timestampMicros;
	}

	public long getTimestampMs() {
		return timestampMicros / 1000;
	}
}
