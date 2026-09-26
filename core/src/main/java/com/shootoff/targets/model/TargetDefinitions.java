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

package com.shootoff.targets.model;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;

import org.xml.sax.Attributes;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import com.shootoff.geom.Point;
import com.shootoff.targets.io.XMLTargetWriter;

/**
 * Reads and writes the .target format (unchanged since ShootOFF 3): a <tt>&lt;target&gt;</tt>
 * element whose attributes are the target's tags, holding <tt>&lt;ellipse&gt;</tt>,
 * <tt>&lt;rectangle&gt;</tt>, <tt>&lt;polygon&gt;</tt> (with <tt>&lt;point x y/&gt;</tt>
 * children) and <tt>&lt;image&gt;</tt> regions, each with optional
 * <tt>&lt;tag name value/&gt;</tt> children. Unknown elements, and tags outside a region, are
 * ignored as they always were.
 */
public final class TargetDefinitions {
	private TargetDefinitions() {}

	/**
	 * Loads a target file. Images resolve through {@link ResourceResolver#files()}.
	 */
	public static TargetDefinition load(Path file) throws TargetFormatException {
		try (InputStream in = Files.newInputStream(file)) {
			return parse(in, ResourceResolver.files(), file.toString()).withFile(file.toFile());
		} catch (final NoSuchFileException e) {
			throw new TargetFormatException(file + ": no such target file", e);
		} catch (final IOException e) {
			throw new TargetFormatException(file + ": " + e.getMessage(), e);
		}
	}

	/**
	 * Loads a target from a stream, e.g. a resource in an exercise's jar. The definition has no
	 * file; see {@link TargetDefinition#withFile(File)}.
	 */
	public static TargetDefinition load(InputStream in, ResourceResolver resolver) throws TargetFormatException {
		return parse(in, resolver, "target stream");
	}

	/**
	 * Writes a target in the .target format.
	 */
	public static void write(TargetDefinition definition, File targetFile) {
		final XMLTargetWriter writer = new XMLTargetWriter(targetFile);

		for (final Region region : definition.regions()) {
			switch (region) {
			case ImageRegion image -> writer.visitImageRegion(image.x(), image.y(), new File(image.imagePath()),
					image.tags());
			case RectangleRegion rectangle -> writer.visitRectangleRegion(rectangle.x(), rectangle.y(),
					rectangle.width(), rectangle.height(), rectangle.fill(), rectangle.tags());
			case EllipseRegion ellipse -> writer.visitEllipse(ellipse.centerX(), ellipse.centerY(), ellipse.radiusX(),
					ellipse.radiusY(), ellipse.fill(), ellipse.tags());
			case PolygonRegion polygon -> {
				final Double[] points = new Double[polygon.points().size() * 2];
				for (int i = 0; i < polygon.points().size(); i++) {
					points[2 * i] = polygon.points().get(i).getX();
					points[2 * i + 1] = polygon.points().get(i).getY();
				}
				writer.visitPolygonRegion(points, polygon.fill(), polygon.tags());
			}
			}
		}

		writer.visitEnd(definition.tags());
	}

	private static TargetDefinition parse(InputStream in, ResourceResolver resolver, String source)
			throws TargetFormatException {
		final Handler handler = new Handler(resolver);

		try {
			SAXParserFactory.newInstance().newSAXParser().parse(in, handler);
		} catch (final SAXParseException e) {
			throw new TargetFormatException(source + ":" + e.getLineNumber() + ": " + e.getMessage(), e);
		} catch (SAXException | ParserConfigurationException | IOException e) {
			throw new TargetFormatException(source + ": " + e.getMessage(), e);
		}

		if (!handler.sawTarget) throw new TargetFormatException(source + ": no <target> element");

		return new TargetDefinition(Optional.empty(), handler.targetTags, handler.regions);
	}

	private static final class Handler extends DefaultHandler {
		private final ResourceResolver resolver;
		private final Map<String, String> targetTags = new LinkedHashMap<>();
		private final List<Region> regions = new ArrayList<>();
		private Locator locator;
		private boolean sawTarget = false;

		// The region element being read, or null between regions
		private String regionElement;
		private Map<String, String> regionAttributes;
		private Map<String, String> regionTags;
		private List<Point> polygonPoints;

		Handler(ResourceResolver resolver) {
			this.resolver = resolver;
		}

		@Override
		public void setDocumentLocator(Locator locator) {
			this.locator = locator;
		}

		@Override
		public void startElement(String uri, String localName, String qName, Attributes attributes)
				throws SAXException {
			switch (qName) {
			case "target":
				sawTarget = true;
				for (int i = 0; i < attributes.getLength(); i++) {
					targetTags.put(attributes.getQName(i), attributes.getValue(i));
				}
				break;

			case "ellipse":
			case "rectangle":
			case "polygon":
			case "image":
				if (regionElement != null) throw error("<" + qName + "> inside <" + regionElement + ">");

				regionElement = qName;
				regionAttributes = new HashMap<>();
				for (int i = 0; i < attributes.getLength(); i++) {
					regionAttributes.put(attributes.getQName(i), attributes.getValue(i));
				}
				regionTags = new LinkedHashMap<>();
				polygonPoints = new ArrayList<>();
				break;

			case "point":
				if (!"polygon".equals(regionElement)) throw error("<point> outside a <polygon>");

				polygonPoints.add(new Point(number(attributes.getValue("x"), "point", "x"),
						number(attributes.getValue("y"), "point", "y")));
				break;

			case "tag":
				// Tags outside a region have always been ignored
				if (regionElement == null) break;

				regionTags.put(required(attributes.getValue("name"), "tag", "name"),
						required(attributes.getValue("value"), "tag", "value"));
				break;

			default:
				// Unknown elements have always been ignored
				break;
			}
		}

		@Override
		public void endElement(String uri, String localName, String qName) throws SAXException {
			if (regionElement == null || !regionElement.equals(qName)) return;

			regions.add(region(qName, regions.size()));
			regionElement = null;
		}

		private Region region(String element, int index) throws SAXException {
			switch (element) {
			case "ellipse":
				return new EllipseRegion(index, number("centerX"), number("centerY"), number("radiusX"),
						number("radiusY"), attribute("fill"), regionTags);
			case "rectangle":
				return new RectangleRegion(index, number("x"), number("y"), number("width"), number("height"),
						attribute("fill"), regionTags);
			case "polygon":
				if (polygonPoints.isEmpty()) throw error("<polygon> has no <point>");
				return new PolygonRegion(index, polygonPoints, attribute("fill"), regionTags);
			default:
				return image(index);
			}
		}

		private ImageRegion image(int index) throws SAXException {
			final String path = attribute("file");
			final double x = number("x");
			final double y = number("y");

			try {
				final Optional<InputStream> in = resolver.open(path);
				if (in.isEmpty()) throw error("image " + path + " not found");

				try (InputStream imageStream = in.get()) {
					final int[] size = ImageSizes.read(imageStream, ImageSizes.isGif(path));
					return new ImageRegion(index, x, y, path, size[0], size[1], regionTags);
				}
			} catch (final IOException e) {
				throw error("image " + path + " can't be read: " + e.getMessage());
			}
		}

		private String attribute(String name) throws SAXException {
			return required(regionAttributes.get(name), regionElement, name);
		}

		private double number(String name) throws SAXException {
			return number(regionAttributes.get(name), regionElement, name);
		}

		private String required(String value, String element, String name) throws SAXException {
			if (value == null) throw error("<" + element + "> is missing attribute " + name);
			return value;
		}

		private double number(String value, String element, String name) throws SAXException {
			try {
				return Double.parseDouble(required(value, element, name));
			} catch (final NumberFormatException e) {
				throw error("<" + element + "> attribute " + name + " is not a number: " + value);
			}
		}

		private SAXParseException error(String message) {
			return new SAXParseException(message, locator);
		}
	}
}
