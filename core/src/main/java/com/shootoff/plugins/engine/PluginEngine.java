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

package com.shootoff.plugins.engine;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.shootoff.plugins.ExerciseMetadata;
import com.shootoff.util.VersionChecker;

/**
 * Watch for new plugin jars and manage plugin registration and deletion.
 *
 * @author phrack
 */
public class PluginEngine implements Runnable {
	private static final Logger logger = LoggerFactory.getLogger(PluginEngine.class);
	private final Path pluginDir;
	private final PluginListener pluginListener;
	private final List<ExerciseLoader> loaders;
	private final PathMatcher jarMatcher = FileSystems.getDefault().getPathMatcher("glob:*.jar");
	private final WatchService watcher = FileSystems.getDefault().newWatchService();
	private final Set<Plugin> plugins = new HashSet<>();

	private final AtomicBoolean watching = new AtomicBoolean(false);

	/**
	 * Lists the built-in exercises and the plugins in the <tt>shootoff.plugins</tt> folder, in the
	 * Training menu's order: standard built-ins, then plugins, then projector built-ins.
	 *
	 * @param loaders
	 *            one per plugin API version the app can run
	 * @param builtIns
	 *            the exercises that ship with the app
	 */
	public PluginEngine(final PluginListener pluginListener, final List<ExerciseLoader> loaders,
			final List<ExerciseEntry> builtIns) throws IOException {
		if (pluginListener == null) {
			throw new IllegalArgumentException("pluginListener cannot be null");
		}

		pluginDir = Paths.get(System.getProperty("shootoff.plugins"));
		this.pluginListener = pluginListener;
		this.loaders = List.copyOf(loaders);

		if (!Files.exists(pluginDir) && !pluginDir.toFile().mkdirs()) {
			logger.error("The path specified by shootoff.plugins doesn't exist and we couldn't create it.");
			return;
		}

		if (!Files.isDirectory(pluginDir)) {
			logger.error("Can't enumerate existing plugins or watch for new plugins because the "
					+ "shootoff.plugins property is not set to a directory");
		}

		for (final ExerciseEntry builtIn : builtIns) {
			if (!builtIn.isProjectorOnly()) pluginListener.registerExercise(builtIn);
		}

		enumerateExistingPlugins();

		for (final ExerciseEntry builtIn : builtIns) {
			if (builtIn.isProjectorOnly()) pluginListener.registerProjectorExercise(builtIn);
		}

		pluginDir.register(watcher, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_DELETE);
	}

	private boolean registerPlugin(final Path jarPath) {
		final Plugin registeringPlugin;

		try {
			registeringPlugin = new Plugin(jarPath, loaders);
		} catch (final Exception e) {
			logger.error("Error creating new plugin", e);
			return false;
		}

		// If the plugin already exists and the new plugin is newer, unregister the old plugin before
		// registering the new one. If the new plugin is actually older, don't load it
		final Optional<Plugin> existingPlugin = findPlugin(registeringPlugin);

		if (existingPlugin.isPresent()) {
			final Plugin existing = existingPlugin.get();
			final ExerciseMetadata existingMetadata = existing.getEntry().metadata();
			final ExerciseMetadata registeringMetadata = registeringPlugin.getEntry().metadata();

			if (VersionChecker.compareVersions(existingMetadata.getVersion(), registeringMetadata.getVersion()) == -1) {
				logger.debug("Registering plugin ({}, {}, {}, {}) is a newer duplicate of an "
						+ "already registered plugin ({}, {}, {}, {})", registeringMetadata.getName(),
						registeringMetadata.getVersion(), registeringMetadata.getCreator(),
						registeringPlugin.getJarPath(), existingMetadata.getName(), existingMetadata.getVersion(),
						existingMetadata.getCreator(), existing.getJarPath());
				unregisterPlugin(existing);
			} else {
				logger.debug("Registering plugin ({}, {}, {}, {}) is an older or same version duplicate of an "
						+ "already registered plugin ({}, {}, {}, {})", registeringMetadata.getName(),
						registeringMetadata.getVersion(), registeringMetadata.getCreator(),
						registeringPlugin.getJarPath(), existingMetadata.getName(), existingMetadata.getVersion(),
						existingMetadata.getCreator(), existing.getJarPath());
				return false;
			}
		}

		if (plugins.add(registeringPlugin)) {
			if (registeringPlugin.getEntry().isProjectorOnly()) {
				pluginListener.registerProjectorExercise(registeringPlugin.getEntry());
			} else {
				pluginListener.registerExercise(registeringPlugin.getEntry());
			}
		}

		return true;
	}

	private void unregisterPlugin(Plugin plugin) {
		pluginListener.unregisterExercise(plugin.getEntry());
		plugins.remove(plugin);
	}

	private void enumerateExistingPlugins() {
		try (Stream<Path> files = Files.walk(pluginDir)) {
			files.forEach(filePath -> {
				if (Files.isRegularFile(filePath) && jarMatcher.matches(filePath.getFileName())) {
					registerPlugin(filePath);
				}
			});
		} catch (final IOException e) {
			logger.error("Error enumerating existing external plugins", e);
		}
	}

	// Plugins are the same exercise if they have the same name and creator
	private Optional<Plugin> findPlugin(Plugin plugin) {
		final ExerciseMetadata newMetadata = plugin.getEntry().metadata();

		for (final Plugin p : plugins) {
			final ExerciseMetadata existingMetadata = p.getEntry().metadata();

			if (existingMetadata.getName().equals(newMetadata.getName())
					&& existingMetadata.getCreator().equals(newMetadata.getCreator())) {
				return Optional.of(p);
			}
		}

		return Optional.empty();
	}

	public Set<Plugin> getPlugins() {
		return plugins;
	}

	/**
	 * @return the plugin whose exercise has <tt>metadata</tt>; empty for a built-in exercise
	 */
	public Optional<Plugin> getPlugin(ExerciseMetadata metadata) {
		for (final Plugin p : plugins) {
			if (p.getEntry().metadata().equals(metadata)) return Optional.of(p);
		}

		return Optional.empty();
	}

	/**
	 * Start watching for plugin creation and deletion in shootoff.plugins.
	 * Plugin jar creation or deletion leads to a plugin registration or
	 * unregistration.
	 */
	public void startWatching() {
		watching.set(true);
		new Thread(this, "Plugin Watcher").start();
	}

	/**
	 * Stop watching for plugin creation and deletion in shootoff.plugins.
	 */
	public void stopWatching() {
		watching.set(false);
	}

	@Override
	public void run() {
		logger.debug("Starting to watch plugins directory");

		while (watching.get()) {
			WatchKey key;
			try {
				key = watcher.poll(1, TimeUnit.SECONDS);

				if (key == null) continue;
			} catch (final InterruptedException e) {
				logger.error("Plugin watcher service was interrupted", e);
				return;
			}

			for (final WatchEvent<?> event : key.pollEvents()) {
				if (StandardWatchEventKinds.OVERFLOW.equals(event.kind())) {
					continue;
				}

				@SuppressWarnings("unchecked")
				final WatchEvent<Path> ev = (WatchEvent<Path>) event;
				final Path updatedFile = ev.context();

				if (!jarMatcher.matches(updatedFile)) {
					continue;
				}

				final Path fqUpdatedFile = pluginDir.resolve(updatedFile);

				if (StandardWatchEventKinds.ENTRY_CREATE.equals(event.kind())) {
					if (!registerPlugin(fqUpdatedFile)) continue;
				} else if (StandardWatchEventKinds.ENTRY_DELETE.equals(event.kind())) {
					Optional<Plugin> deletedPlugin = Optional.empty();

					for (final Plugin p : plugins) {
						if (p.getJarPath().equals(fqUpdatedFile)) {
							deletedPlugin = Optional.of(p);
							break;
						}
					}

					if (deletedPlugin.isPresent()) {
						unregisterPlugin(deletedPlugin.get());
					}
				} else {
					logger.warn("Unexpected plugin watcher event {}", event.kind().toString());
				}
			}

			if (!key.reset()) {
				logger.error("Could not reset watch key, cannot receive further plugin watch events");
				watching.set(false);
			}
		}

		try {
			watcher.close();
		} catch (final IOException e) {
			logger.error("Error when stopping plugins directory watcher", e);
		}

		logger.debug("Stopped watching plugins directory");
	}
}
