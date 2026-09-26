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

package com.shootoff.gui.exercise;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.util.Optional;

import javax.sound.sampled.LineEvent;

import com.shootoff.plugins.TextToSpeech;
import com.shootoff.sound.SoundPlayer;

/**
 * Where a hosted exercise's sounds go: the speakers, or a recorder in tests.
 */
public interface SoundOutput {
	/**
	 * Plays <tt>sound</tt> and closes it, then runs <tt>whenDone</tt>.
	 *
	 * @param name
	 *            the name the exercise used, for logs and tests
	 */
	void play(String name, InputStream sound, Runnable whenDone);

	void say(String text);

	static SoundOutput speakers() {
		return new SoundOutput() {
			@Override
			public void play(String name, InputStream sound, Runnable whenDone) {
				if (SoundPlayer.isSilenced()) {
					System.out.println(name);
					whenDone.run();
					return;
				}

				SoundPlayer.play(new BufferedInputStream(sound), Optional.of(event -> {
					if (LineEvent.Type.STOP.equals(event.getType())) {
						event.getLine().close();
						whenDone.run();
					}
				}));
			}

			@Override
			public void say(String text) {
				// Synthesis takes a while; keep it off the exercise thread
				new Thread(() -> TextToSpeech.say(text), "Exercise speech").start();
			}
		};
	}
}
