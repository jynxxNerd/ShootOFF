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

package com.shootoff.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import com.shootoff.gui.controller.VideoPlayerController;
import com.shootoff.plugins.TrainingExercise;
import com.shootoff.plugins.engine.Plugin;

import javafx.scene.paint.Color;

/**
 * The JavaFX app's configuration: the persisted {@link Settings} plus the JavaFX app's own runtime
 * state (the current exercise and plugin, open video players and the shot timer row color). The session
 * recorder and the recording cameras are in {@link Settings}, which the shot pipeline in core reads.
 *
 * @author phrack
 */
public class Configuration extends Settings {
	private final Set<VideoPlayerController> videoPlayers = new HashSet<>();
	private TrainingExercise currentExercise = null;
	private Plugin currentPlugin = null;
	private Optional<Color> shotRowColor = Optional.empty();

	/**
	 * @return the current configuration, or <tt>null</tt> if the most recently constructed
	 *         {@link Settings} is not a <tt>Configuration</tt> (only happens in core tests)
	 */
	public static Configuration getConfig() {
		final Settings settings = Settings.getSettings();
		return settings instanceof Configuration ? (Configuration) settings : null;
	}

	protected Configuration(InputStream configInputStream, String name) throws IOException, ConfigurationException {
		super(configInputStream, name);
	}

	protected Configuration(String name) throws IOException, ConfigurationException {
		super(name);
	}

	protected Configuration(InputStream configInputStream, String name, String[] args)
			throws IOException, ConfigurationException {
		super(configInputStream, name, args);
	}

	/**
	 * Loads the configuration from a file named <tt>name</tt> and then updates the
	 * configuration using the programs arguments stored in <tt>args</tt>.
	 *
	 * @param name
	 *            the configuration file to load properties from
	 * @param args
	 *            the command line arguments for this program
	 * @throws IOException
	 *             <tt>name</tt> doesn't exist on the file system
	 * @throws ConfigurationException
	 *             a specific property value is out of spec
	 */
	public Configuration(String name, String[] args) throws IOException, ConfigurationException {
		super(name, args);
	}

	public Configuration(String[] args) throws ConfigurationException {
		super(args);
	}

	public void registerVideoPlayer(VideoPlayerController videoPlayer) {
		videoPlayers.add(videoPlayer);
	}

	public void unregisterVideoPlayer(VideoPlayerController videoPlayer) {
		videoPlayers.remove(videoPlayer);
	}

	public Set<VideoPlayerController> getVideoPlayers() {
		return videoPlayers;
	}

	public void setShotTimerRowColor(Color c) {
		shotRowColor = Optional.ofNullable(c);
	}

	public Optional<Color> getShotTimerRowColor() {
		return shotRowColor;
	}

	public void setExercise(TrainingExercise exercise) {
		if (currentExercise != null) currentExercise.destroy();

		currentExercise = exercise;
	}

	public Optional<TrainingExercise> getExercise() {
		return Optional.ofNullable(currentExercise);
	}

	public void setPlugin(Plugin plugin) {
		currentPlugin = plugin;
	}

	public Optional<Plugin> getPlugin() {
		return Optional.ofNullable(currentPlugin);
	}
}
