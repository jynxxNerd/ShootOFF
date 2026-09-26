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
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonIOException;
import com.google.gson.JsonObject;
import com.shootoff.camera.Shot;

public class JSONSessionWriter implements EventVisitor {
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

	private final Logger logger = LoggerFactory.getLogger(JSONSessionWriter.class);

	private final File sessionFile;
	private final JsonArray cameras = new JsonArray();
	private JsonObject currentCamera;
	private JsonArray currentCameraEvents;

	public JSONSessionWriter(File sessionFile) {
		this.sessionFile = sessionFile;
	}

	@Override
	public void visitCamera(String cameraName) {
		currentCamera = new JsonObject();
		currentCamera.addProperty("name", cameraName);

		currentCameraEvents = new JsonArray();
	}

	@Override
	public void visitCameraEnd() {
		currentCamera.add("events", currentCameraEvents);
		cameras.add(currentCamera);
	}

	@Override
	public void visitShot(long timestamp, Shot shot, int markerRadius, boolean isMalfunction, boolean isReload,
			Optional<Integer> targetIndex, Optional<Integer> hitRegionIndex, Optional<String> videoString) {
		final JsonObject event = new JsonObject();
		event.addProperty("type", "shot");
		event.addProperty("timestamp", timestamp);
		event.addProperty("color", SessionColors.paintString(shot.getColor()));
		event.addProperty("x", shot.getX());
		event.addProperty("y", shot.getY());
		event.addProperty("shotTimestamp", shot.getTimestamp());
		event.addProperty("markerRadius", markerRadius);
		event.addProperty("isMalfunction", isMalfunction);
		event.addProperty("isReload", isReload);
		event.addProperty("targetIndex", targetIndex.orElse(-1));
		event.addProperty("hitRegionIndex", hitRegionIndex.orElse(-1));
		if (videoString.isPresent()) {
			event.addProperty("videos", videoString.get());
		}
		currentCameraEvents.add(event);
	}

	@Override
	public void visitTargetAdd(long timestamp, String targetName) {
		final JsonObject event = new JsonObject();
		event.addProperty("type", "targetAdded");
		event.addProperty("timestamp", timestamp);
		event.addProperty("name", targetName);

		currentCameraEvents.add(event);
	}

	@Override
	public void visitTargetRemove(long timestamp, int targetIndex) {
		final JsonObject event = new JsonObject();
		event.addProperty("type", "targetRemoved");
		event.addProperty("timestamp", timestamp);
		event.addProperty("index", targetIndex);

		currentCameraEvents.add(event);
	}

	@Override
	public void visitTargetResize(long timestamp, int targetIndex, double newWidth, double newHeight) {
		final JsonObject event = new JsonObject();
		event.addProperty("type", "targetResized");
		event.addProperty("timestamp", timestamp);
		event.addProperty("index", targetIndex);
		event.addProperty("newWidth", newWidth);
		event.addProperty("newHeight", newHeight);

		currentCameraEvents.add(event);
	}

	@Override
	public void visitTargetMove(long timestamp, int targetIndex, int newX, int newY) {
		final JsonObject event = new JsonObject();
		event.addProperty("type", "targetMoved");
		event.addProperty("timestamp", timestamp);
		event.addProperty("index", targetIndex);
		event.addProperty("newX", newX);
		event.addProperty("newY", newY);

		currentCameraEvents.add(event);
	}

	@Override
	public void visitExerciseFeedMessage(long timestamp, String message) {
		final JsonObject event = new JsonObject();
		event.addProperty("type", "exerciseFeedMessage");
		event.addProperty("timestamp", timestamp);
		event.addProperty("message", message);

		currentCameraEvents.add(event);
	}

	@Override
	public void visitEnd() {
		final JsonObject session = new JsonObject();
		session.add("cameras", cameras);

		try (Writer file = new OutputStreamWriter(new FileOutputStream(sessionFile), StandardCharsets.UTF_8)) {
			GSON.toJson(session, file);
		} catch (final IOException | JsonIOException e) {
			logger.error("Error writing JSON session", e);
		}
	}
}
