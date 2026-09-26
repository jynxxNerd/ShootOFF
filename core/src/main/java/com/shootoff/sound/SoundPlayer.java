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

package com.shootoff.sound;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineEvent;
import javax.sound.sampled.LineListener;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.UnsupportedAudioFileException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Plays sound files and streams asynchronously. Used by shot processors and exercises.
 */
public final class SoundPlayer {
	private static final Logger logger = LoggerFactory.getLogger(SoundPlayer.class);

	private static volatile boolean isSilenced = false;

	private SoundPlayer() {}

	/**
	 * Allows sounds to be silenced or on. If silenced, instead of playing a sound the file name
	 * is printed to stdout. This exists so that components can be easily tested even if they
	 * rely on sounds.
	 *
	 * @param isSilenced
	 *            set to <tt>true</tt> if sound file names should instead be printed to stdout,
	 *            <tt>false</tt> for normal operation.
	 */
	public static void silence(boolean isSilenced) {
		SoundPlayer.isSilenced = isSilenced;
	}

	public static boolean isSilenced() {
		return isSilenced;
	}

	/**
	 * @return <tt>soundFile</tt> if it is absolute, otherwise <tt>soundFile</tt> resolved
	 *         against the <tt>shootoff.home</tt> folder
	 */
	public static File resolve(File soundFile) {
		if (soundFile.isAbsolute()) return soundFile;

		return new File(System.getProperty("shootoff.home") + File.separator + soundFile.getPath());
	}

	/**
	 * Plays an audio file asynchronously.
	 *
	 * @param soundFilePath
	 *            the audio file to play (e.g. "sounds/metal_clang.wav")
	 */
	public static void play(String soundFilePath) {
		play(new File(soundFilePath));
	}

	public static void play(File soundFile) {
		playFile(soundFile, Optional.empty());
	}

	public static void play(InputStream is) {
		play(is, Optional.empty());
	}

	public static void play(InputStream is, Optional<LineListener> listener) {
		if (isSilenced) {
			System.out.println("Playing audio for modular exercise.");
			return;
		}

		try {
			final AudioInputStream audioInputStream = AudioSystem.getAudioInputStream(is);
			playStream(audioInputStream, listener);
		} catch (UnsupportedAudioFileException | IOException e) {
			logger.error("Error reading sound stream to play", e);
		}
	}

	/**
	 * Plays the files one after another.
	 */
	public static void playAll(List<File> soundFiles) {
		if (isSilenced) {
			soundFiles.forEach(System.out::println);
		} else {
			final SoundQueue sq = new SoundQueue(soundFiles);
			sq.play();
		}
	}

	private static void playFile(File soundFile, Optional<LineListener> listener) {
		if (isSilenced) {
			System.out.println(soundFile.getPath());
			return;
		}

		final File resolvedFile = resolve(soundFile);

		try {
			final AudioInputStream audioInputStream = AudioSystem.getAudioInputStream(resolvedFile);
			playStream(audioInputStream, listener);
		} catch (UnsupportedAudioFileException | IOException e) {
			logger.error(String.format("Error reading sound file to play: soundFile = %s", resolvedFile), e);
		}
	}

	private static void playStream(AudioInputStream audioInputStream, Optional<LineListener> listener) {
		final AudioFormat format = audioInputStream.getFormat();
		final DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);

		SourceDataLine line = null;

		try {
			line = (SourceDataLine) AudioSystem.getLine(info);

			line.open(format);
			line.start();

			if (listener.isPresent()) {
				line.addLineListener(listener.get());
			} else {
				line.addLineListener((e) -> {
					if (LineEvent.Type.STOP.equals(e.getType())) {
						e.getLine().close();
						try {
							audioInputStream.close();
						} catch (final Exception e1) {
							logger.error("Error closing audio input stream", e1);
						}
					}
				});
			}

			final SourceDataLine sourceLine = line;
			new Thread(() -> {
				int nBytesRead = 0;
				final byte[] abData = new byte[1024];
				while (nBytesRead != -1) {
					try {
						nBytesRead = audioInputStream.read(abData, 0, abData.length);
					} catch (final IOException e) {
						logger.error("Error playing sound clip", e);
					}
					if (nBytesRead >= 0) {
						sourceLine.write(abData, 0, nBytesRead);
					}
				}

				sourceLine.drain();
				sourceLine.close();
			}).start();
		} catch (final LineUnavailableException e) {
			if (line != null) line.close();
			logger.error("Error playing sound clip", e);
		}
	}

	private static class SoundQueue implements LineListener {
		private final List<File> soundFiles;
		private int queueIndex = 0;

		public SoundQueue(List<File> soundFiles) {
			this.soundFiles = soundFiles;
		}

		public void play() {
			playFile(soundFiles.get(queueIndex), Optional.of(this));
		}

		@Override
		public void update(final LineEvent event) {
			if (LineEvent.Type.STOP.equals(event.getType())) {
				event.getLine().close();

				queueIndex++;

				if (queueIndex < soundFiles.size()) {
					playFile(soundFiles.get(queueIndex), Optional.of(this));
				}
			}
		}
	}
}
