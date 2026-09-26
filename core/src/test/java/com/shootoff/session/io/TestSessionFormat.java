package com.shootoff.session.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.shootoff.camera.Shot;
import com.shootoff.camera.shot.ShotColor;
import com.shootoff.session.Event;
import com.shootoff.session.SessionRecorder;
import com.shootoff.session.ShotEvent;

class TestSessionFormat {
	@TempDir Path temp;

	private static SessionRecorder twoShots() {
		final List<Event> events = new ArrayList<>();
		events.add(new ShotEvent("Default", 5, new Shot(ShotColor.RED, 10, 11.5, 3), 2, false, true, Optional.of(0),
				Optional.of(1), Optional.of("cam:a/b.mp4")));
		events.add(new ShotEvent("Default", 6, new Shot(ShotColor.INFRARED, 1, 2, 4), 5, true, false,
				Optional.empty(), Optional.empty(), Optional.empty()));

		final SessionRecorder recorder = new SessionRecorder();
		recorder.addEvents(Map.of("Default", events));
		return recorder;
	}

	@Test
	void xmlShotsKeepTheirFormat() throws IOException {
		final String sessions = System.getProperty("shootoff.sessions");
		System.setProperty("shootoff.sessions", temp.toString());

		try {
			final File file = temp.resolve("session.xml").toFile();
			SessionIO.saveSession(twoShots(), file);

			// With videos the color is JavaFX's paint string, without them the color's name, as ever
			assertEquals(List.of("<?xml version=\"1.0\" encoding=\"UTF-8\"?>", "<session>",
					"\t<camera name=\"Default\">",
					"\t\t<shot timestamp=\"5\" color=\"0xff0000ff\" x=\"10.000000\" y=\"11.500000\" shotTimestamp=\"3\""
							+ " markerRadius=\"2\" isMalfunction=\"false\" isReload=\"true\" targetIndex=\"0\""
							+ " hitRegionIndex=\"1\" videos=\"cam:a/b.mp4\" />",
					"\t\t<shot timestamp=\"6\" color=\"INFRARED\" x=\"1.000000\" y=\"2.000000\" shotTimestamp=\"4\""
							+ " markerRadius=\"5\" isMalfunction=\"true\" isReload=\"false\" targetIndex=\"-1\""
							+ " hitRegionIndex=\"-1\" />",
					"\t</camera>", "</session>"), Files.readAllLines(file.toPath()));
		} finally {
			if (sessions == null) {
				System.clearProperty("shootoff.sessions");
			} else {
				System.setProperty("shootoff.sessions", sessions);
			}
		}
	}

	@Test
	void jsonShotsKeepTheJavaFxColorStrings() throws IOException {
		final File file = temp.resolve("session.json").toFile();
		SessionIO.saveSession(twoShots(), file);

		final JsonArray events;
		try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
			events = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonArray("cameras").get(0)
					.getAsJsonObject().getAsJsonArray("events");
		}

		final JsonObject red = events.get(0).getAsJsonObject();
		assertEquals("0xff0000ff", red.get("color").getAsString());
		assertEquals(2, red.get("markerRadius").getAsInt());
		assertEquals("cam:a/b.mp4", red.get("videos").getAsString());

		final JsonObject infrared = events.get(1).getAsJsonObject();
		assertEquals("0xffa500ff", infrared.get("color").getAsString());
		assertEquals(5, infrared.get("markerRadius").getAsInt());
		assertEquals(-1, infrared.get("targetIndex").getAsInt());
		assertFalse(infrared.has("videos"));
	}
}
