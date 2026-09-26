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

package com.shootoff.courses.io;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import com.shootoff.courses.Course;
import com.shootoff.courses.CourseBackground;
import com.shootoff.courses.CourseTarget;
import com.shootoff.geom.Size;

/**
 * Reads a .course file. Target files are only named here; the arena loads them when it applies
 * the course.
 */
public class XMLCourseReader {
	private static final Logger logger = LoggerFactory.getLogger(XMLCourseReader.class);

	private final File courseFile;

	public XMLCourseReader(File courseFile) {
		this.courseFile = courseFile;
	}

	public Optional<Course> load() {
		try (InputStream xmlInput = new FileInputStream(courseFile)) {
			final CourseXMLHandler handler = new CourseXMLHandler();
			SAXParserFactory.newInstance().newSAXParser().parse(xmlInput, handler);

			return Optional.of(new Course(handler.background, handler.targets, handler.resolution));
		} catch (IOException | ParserConfigurationException | SAXException | NumberFormatException e) {
			logger.error("Error reading XML course", e);
		}

		return Optional.empty();
	}

	private static class CourseXMLHandler extends DefaultHandler {
		private Optional<CourseBackground> background = Optional.empty();
		private final List<CourseTarget> targets = new ArrayList<>();
		private Optional<Size> resolution = Optional.empty();

		@Override
		public void startElement(String uri, String localName, String qName, Attributes attributes)
				throws SAXException {
			switch (qName) {
			case "background":
				background = Optional.of(new CourseBackground(attributes.getValue("url"),
						Boolean.parseBoolean(attributes.getValue("isResource"))));
				break;

			case "target":
				targets.add(new CourseTarget(new File(attributes.getValue("file")),
						Double.parseDouble(attributes.getValue("x")), Double.parseDouble(attributes.getValue("y")),
						Double.parseDouble(attributes.getValue("width")),
						Double.parseDouble(attributes.getValue("height"))));
				break;

			case "resolution":
				resolution = Optional.of(new Size(Double.parseDouble(attributes.getValue("width")),
						Double.parseDouble(attributes.getValue("height"))));
				break;
			}
		}
	}
}
