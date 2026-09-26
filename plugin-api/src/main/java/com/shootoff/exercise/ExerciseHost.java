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

package com.shootoff.exercise;

import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

import com.shootoff.geom.Size;

/**
 * What a user interface offers a running {@link Exercise}. Methods may be called from any thread;
 * each user interface moves the work onto its own thread. Every callback the host makes (scheduled
 * tasks, buttons, settings, listeners) runs on the exercise's thread. After the exercise stops, calls
 * change nothing.
 * <p>
 * Names of targets, sounds and resources are looked up in the exercise's own jar first (a leading
 * <tt>@</tt> or <tt>/</tt> is dropped). Otherwise they are files: absolute, relative to the ShootOFF
 * folder, or relative to its <tt>targets/</tt> or <tt>sounds/</tt> folder.
 */
public interface ExerciseHost {
	/**
	 * @return the size of the surface the exercise places things on: the arena, or the camera feed
	 */
	Size surfaceSize();

	/**
	 * @return <tt>true</tt> if the surface is the projector arena
	 */
	boolean isProjector();

	/**
	 * Sets the arena's background to an image from the exercise's jar or ShootOFF's bundled
	 * backgrounds (e.g. <tt>arena/backgrounds/indoor_range.gif</tt>). The previous background comes
	 * back when the exercise stops. Ignored on a camera feed.
	 */
	void setBackground(String imageResource);

	/**
	 * Adds a target with its top-left corner at (<tt>x</tt>, <tt>y</tt>).
	 *
	 * @return the target, or empty if the file can't be found or read
	 */
	Optional<TargetHandle> addTarget(String targetFile, double x, double y);

	/**
	 * @return every target on the surface, bottom to top, including the user's
	 */
	List<TargetHandle> targets();

	TextHandle showText(String text, double x, double y, TextStyle style);

	/**
	 * Shows a message in the standard banner on every camera feed, and records it in the session
	 * being recorded, if any.
	 */
	void showMessage(String message);

	/**
	 * Adds a button next to ShootOFF's Reset button.
	 */
	ButtonHandle addButton(String label, Runnable onClick);

	/**
	 * Adds a numeric setting to the exercise pane. <tt>onChange</tt> hears every value the user sets.
	 */
	void addNumberSetting(String label, double initial, double min, double max, double step, DoubleConsumer onChange);

	/**
	 * Adds a column to the shot timer.
	 */
	void addColumn(String name);

	/**
	 * Sets a column's value in the shot timer's latest row: the latest shot's, or the latest
	 * {@link #addTimerRow} row.
	 */
	void setColumnValue(String name, String value);

	/**
	 * Highlights the shot timer's latest row.
	 */
	void styleLastRow(RowStyle style);

	/**
	 * Adds a shot timer row that no shot made, for example for a par time that ran out, and makes it
	 * the latest row. It reads like a red shot's row: Time shows <tt>timeMillis</tt> in seconds, Split
	 * the time since the previous row, Laser "red". It is highlighted with <tt>style</tt>. It is not a
	 * shot: no marker, no hit, no {@link Exercise#onShot} and no session event.
	 */
	void addTimerRow(long timeMillis, RowStyle style);

	/**
	 * Draws a shot marker, for example to replay shots on a summary target.
	 */
	ShotMarkerHandle showShotMarker(double x, double y, ShotStyle style);

	/**
	 * Clears ShootOFF's shot markers and shot timer, and the markers the exercise drew.
	 */
	void clearShots();

	void pauseShotDetection(boolean paused);

	void playSound(String resourceOrFile);

	/**
	 * Plays the sounds one after another.
	 */
	void playSounds(List<String> resourcesOrFiles);

	/**
	 * Speaks <tt>text</tt> (text to speech).
	 */
	void say(String text);

	/**
	 * @return the host's clock in milliseconds. Use it to time shots, so tests can control time.
	 */
	long currentTimeMillis();

	/**
	 * Runs <tt>task</tt> on the exercise's thread after <tt>delay</tt>.
	 */
	Cancellable schedule(Runnable task, Duration delay);

	Cancellable scheduleRepeating(Runnable task, Duration initialDelay, Duration period);

	/**
	 * @return a resource from the exercise's own jar
	 */
	Optional<InputStream> resource(String path);

	/**
	 * @return a writable folder for this exercise's own files (for example personal bests); it exists
	 */
	Path dataDirectory();

	/**
	 * @return the par time in seconds shown in ShootOFF's shared par-time control
	 */
	double parTime();

	/**
	 * Sets the shared par-time control, for example to the exercise's default. The exercise's own
	 * listeners aren't called.
	 */
	void setParTime(double seconds);

	/**
	 * Shows the shared par-time control while the exercise runs; <tt>listener</tt> hears the values
	 * the user sets.
	 */
	void onParTimeChanged(DoubleConsumer listener);

	/**
	 * @return the random start delay shown in ShootOFF's shared delay controls
	 */
	DelayRange delayedStart();

	/**
	 * Sets the shared delay controls. The exercise's own listeners aren't called.
	 */
	void setDelayedStart(DelayRange range);

	/**
	 * Shows the shared delay controls while the exercise runs; <tt>listener</tt> hears the ranges the
	 * user sets.
	 */
	void onDelayedStartChanged(Consumer<DelayRange> listener);
}
