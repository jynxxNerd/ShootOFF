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

package com.shootoff.targets.animation;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;

import com.shootoff.targets.model.GifFrames;
import com.shootoff.targets.model.GifFrames.GifFrame;

import javafx.scene.image.ImageView;
import javafx.util.Duration;

public class GifAnimation extends SpriteAnimation {
	private static ImageFrame[] frames;

	public GifAnimation(ImageView imageView, InputStream gifStream) throws IOException {
		super(imageView, readGif(gifStream));

		int delay = frames[0].getDelay();
		if (delay < 1) delay = SpriteAnimation.DEFAULT_DELAY;

		setCycleDuration(Duration.millis(delay));
	}

	public GifAnimation(ImageView imageView, File gifFile) throws FileNotFoundException, IOException {
		super(imageView, readGif(new FileInputStream(gifFile)));

		int delay = frames[0].getDelay();
		if (delay < 1) delay = SpriteAnimation.DEFAULT_DELAY;

		setCycleDuration(Duration.millis(delay));
	}

	// The frames are core's GifFrames: whole frames, composed as the GIF's disposal methods say
	private static ImageFrame[] readGif(InputStream stream) throws IOException {
		final ArrayList<ImageFrame> frames = new ArrayList<>(2);

		for (final GifFrame frame : GifFrames.read(stream)) {
			frames.add(new ImageFrame(frame.image(), frame.delayMillis(), frame.disposal()));
		}

		GifAnimation.frames = frames.toArray(new ImageFrame[frames.size()]);
		return GifAnimation.frames;
	}
}
