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
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.nio.file.Path;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * A plugin jar's <tt>shootoff.xml</tt>:
 * <tt>&lt;shootoffExercise apiVersion="2" exerciseClass="..." /&gt;</tt>. A descriptor without
 * <tt>apiVersion</tt> is a v1 plugin.
 */
public record PluginDescriptor(int apiVersion, String exerciseClass) {
	public static final String FILE_NAME = "shootoff.xml";

	private static final Logger logger = LoggerFactory.getLogger(PluginDescriptor.class);

	/**
	 * Reads the descriptor in <tt>loader</tt>'s own jar. The classpath is never searched: it may hold
	 * ShootOFF's or another plugin's descriptor.
	 *
	 * @throws IllegalArgumentException
	 *             if the jar has no descriptor, or it is malformed
	 */
	public static PluginDescriptor read(URLClassLoader loader, Path jarPath) throws IOException {
		final URL url = loader.findResource(FILE_NAME);
		if (url == null) {
			throw new IllegalArgumentException(
					String.format("The jarPath %s does not represent a valid ShootOFF plugin", jarPath));
		}

		final DescriptorHandler handler = new DescriptorHandler();

		// Don't cache the jar connection, otherwise the jar stays open and can't be replaced or deleted
		// while ShootOFF runs
		final URLConnection connection = url.openConnection();
		connection.setUseCaches(false);
		try (InputStream in = connection.getInputStream()) {
			SAXParserFactory.newInstance().newSAXParser().parse(in, handler);
		} catch (final ParserConfigurationException | SAXException e) {
			throw new IllegalArgumentException(
					String.format("%s's %s is malformed: %s", jarPath, FILE_NAME, e.getMessage()), e);
		}

		if (handler.exerciseClass == null) {
			throw new IllegalArgumentException(
					String.format("%s's %s doesn't name an exerciseClass", jarPath, FILE_NAME));
		}

		return new PluginDescriptor(handler.apiVersion, handler.exerciseClass);
	}

	private static final class DescriptorHandler extends DefaultHandler {
		private int apiVersion = 1;
		private String exerciseClass = null;

		@Override
		public void startElement(String uri, String localName, String qName, Attributes attributes)
				throws SAXException {
			if (!"shootoffExercise".equals(qName)) {
				logger.warn("Unrecognized exercise settings tag ignored: {}", qName);
				return;
			}

			exerciseClass = attributes.getValue("exerciseClass");

			final String version = attributes.getValue("apiVersion");
			if (version != null) {
				try {
					apiVersion = Integer.parseInt(version.trim());
				} catch (final NumberFormatException e) {
					throw new SAXException("apiVersion must be a whole number, not " + version);
				}
			}
		}
	}
}
