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
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.nio.file.Path;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import com.shootoff.plugins.ProjectorTrainingExerciseBase;
import com.shootoff.plugins.TrainingExercise;
import com.shootoff.plugins.TrainingExerciseBase;

public class Plugin {
	private static final Logger logger = LoggerFactory.getLogger(Plugin.class);

	private final URLClassLoader loader;
	private final TrainingExercise exercise;
	private final Path jarPath;
	private final PluginType type;

	public Plugin(final Path jarPath) throws ParserConfigurationException, SAXException, IOException {
		this.jarPath = jarPath;

		try {
			loader = new URLClassLoader(new URL[] { jarPath.toUri().toURL() },
					Thread.currentThread().getContextClassLoader());
		} catch (final MalformedURLException e) {
			throw new IllegalArgumentException(
					String.format("The jarPath %s does not represent a valid ShootOFF plugin", jarPath), e);
		}

		// findResource only searches this plugin's jar. getResourceAsStream
		// would search ShootOFF's classpath first and could find another
		// plugin descriptor.
		final URL pluginSettingsUrl = loader.findResource("shootoff.xml");

		if (pluginSettingsUrl == null) {
			throw new IllegalArgumentException(
					String.format("The jarPath %s does not represent a valid ShootOFF plugin", jarPath));
		}

		final SAXParser saxParser = SAXParserFactory.newInstance().newSAXParser();
		final PluginSettingsXMLHandler handler = new PluginSettingsXMLHandler();

		// Don't cache the jar connection, otherwise the jar stays open and
		// can't be replaced or deleted while ShootOFF runs
		final URLConnection connection = pluginSettingsUrl.openConnection();
		connection.setUseCaches(false);
		try (InputStream pluginSettings = connection.getInputStream()) {
			saxParser.parse(pluginSettings, handler);
		}

		if (handler.getExercise() == null) {
			throw new IllegalArgumentException(
					String.format("Could not fetch main class for newly discovered exercise at %s", jarPath));
		}

		exercise = handler.getExercise();
		type = handler.getType();
	}

	private class PluginSettingsXMLHandler extends DefaultHandler {
		private TrainingExercise exercise;
		private PluginType type;

		public TrainingExercise getExercise() {
			return exercise;
		}

		public PluginType getType() {
			return type;
		}

		@Override
		public void startElement(String uri, String localName, String qName, Attributes attributes) {
			switch (qName) {
			case "shootoffExercise": {
				final String mainClassName = attributes.getValue("exerciseClass");
				final Class<?> exerciseClass;
				try {
					exerciseClass = loader.loadClass(mainClassName);
				} catch (final ClassNotFoundException e) {
					logger.error("Configured exerciseClass ({}) was not found", mainClassName);
					return;
				}

				// No superclass or not a known training exercise superclass
				final boolean isStandardExercise = exerciseClass.getSuperclass().getName()
						.equals(TrainingExerciseBase.class.getName());
				final boolean isProjectorOnlyExercise = exerciseClass.getSuperclass().getName()
						.equals(ProjectorTrainingExerciseBase.class.getName());

				if (exerciseClass.getSuperclass() == null || (!isStandardExercise && !isProjectorOnlyExercise)) {
					final String superclassName;
					if (exerciseClass.getSuperclass() == null) {
						superclassName = "null";
					} else {
						superclassName = exerciseClass.getSuperclass().getName();
					}

					logger.error(
							"Configured exerciseClass ({}) does not have a known training exercise superclass, type is {}",
							mainClassName, superclassName);
					return;
				}

				if (isStandardExercise) {
					type = PluginType.STANDARD;
				} else if (isProjectorOnlyExercise) {
					type = PluginType.PROJECTOR_ONLY;
				}

				try {
					exercise = (TrainingExercise) exerciseClass.getDeclaredConstructor().newInstance();
				} catch (final ReflectiveOperationException e) {
					logger.error("Error instantiating configured exerciseClass", e);
				}
			}
			break;

			default:
				logger.warn("Unrecognized exercise settings tag ignored: {}", qName);
			}
		}
	}

	public URLClassLoader getLoader() {
		return loader;
	}

	public TrainingExercise getExercise() {
		return exercise;
	}

	public Path getJarPath() {
		return jarPath;
	}

	public PluginType getType() {
		return type;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((jarPath == null) ? 0 : jarPath.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) return true;
		if (obj == null) return false;
		if (getClass() != obj.getClass()) return false;
		final Plugin other = (Plugin) obj;
		if (jarPath == null) {
			if (other.jarPath != null) return false;
		} else if (!jarPath.equals(other.jarPath)) return false;
		return true;
	}
}