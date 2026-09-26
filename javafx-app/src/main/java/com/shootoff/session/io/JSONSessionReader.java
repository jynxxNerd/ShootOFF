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

package com.shootoff.session.io;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.shootoff.camera.shot.DisplayShot;
import com.shootoff.camera.shot.ShotColor;
import com.shootoff.session.Event;
import com.shootoff.session.ExerciseFeedMessageEvent;
import com.shootoff.session.ShotEvent;
import com.shootoff.session.TargetAddedEvent;
import com.shootoff.session.TargetMovedEvent;
import com.shootoff.session.TargetRemovedEvent;
import com.shootoff.session.TargetResizedEvent;

public class JSONSessionReader {
	private final Logger logger = LoggerFactory.getLogger(JSONSessionReader.class);

	private final File sessionFile;

	public JSONSessionReader(File sessionFile) {
		this.sessionFile = sessionFile;
	}

	public Map<String, List<Event>> load() {
		final Map<String, List<Event>> events = new HashMap<>();

		try (Reader reader = new InputStreamReader(new FileInputStream(sessionFile), StandardCharsets.UTF_8)) {
			final JsonObject session = JsonParser.parseReader(reader).getAsJsonObject();

			for (final JsonElement cameraElement : session.getAsJsonArray("cameras")) {
				final JsonObject camera = cameraElement.getAsJsonObject();

				final String cameraName = camera.get("name").getAsString();
				final List<Event> cameraEvents = new ArrayList<>();
				events.put(cameraName, cameraEvents);

				for (final JsonElement eventElement : camera.getAsJsonArray("events")) {
					final JsonObject event = eventElement.getAsJsonObject();
					final long timestamp = event.get("timestamp").getAsLong();

					switch (event.get("type").getAsString()) {
					case "shot":
						final DisplayShot shot = new DisplayShot(parseColor(event.get("color").getAsString()),
								event.get("x").getAsDouble(), event.get("y").getAsDouble(),
								event.get("shotTimestamp").getAsLong(), event.get("markerRadius").getAsInt());

						final Optional<String> videoString = event.has("videos")
								? Optional.of(event.get("videos").getAsString()) : Optional.empty();

						cameraEvents.add(new ShotEvent(cameraName, timestamp, shot,
								event.get("isMalfunction").getAsBoolean(), event.get("isReload").getAsBoolean(),
								optionalIndex(event, "targetIndex"), optionalIndex(event, "hitRegionIndex"),
								videoString));
						break;

					case "targetAdded":
						cameraEvents.add(new TargetAddedEvent(cameraName, timestamp, event.get("name").getAsString()));
						break;

					case "targetRemoved":
						cameraEvents.add(new TargetRemovedEvent(cameraName, timestamp, event.get("index").getAsInt()));
						break;

					case "targetResized":
						cameraEvents.add(new TargetResizedEvent(cameraName, timestamp, event.get("index").getAsInt(),
								event.get("newWidth").getAsDouble(), event.get("newHeight").getAsDouble()));
						break;

					case "targetMoved":
						cameraEvents.add(new TargetMovedEvent(cameraName, timestamp, event.get("index").getAsInt(),
								event.get("newX").getAsInt(), event.get("newY").getAsInt()));
						break;

					case "exerciseFeedMessage":
						cameraEvents.add(
								new ExerciseFeedMessageEvent(cameraName, timestamp, event.get("message").getAsString()));
						break;
					}
				}
			}
		} catch (IOException | JsonParseException | IllegalStateException e) {
			logger.error("Error reading JSON session", e);
		}

		return events;
	}

	// Older sessions stored JavaFX paint strings instead of color names
	private static ShotColor parseColor(String color) {
		if ("0xff0000ff".equals(color) || "RED".equals(color)) {
			return ShotColor.RED;
		} else if ("0xffa500ff".equals(color) || "INFRARED".equals(color)) {
			return ShotColor.INFRARED;
		} else {
			return ShotColor.GREEN;
		}
	}

	private static Optional<Integer> optionalIndex(JsonObject event, String key) {
		final int index = event.get(key).getAsInt();
		return index == -1 ? Optional.empty() : Optional.of(index);
	}
}
