package com.shootoff.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.shootoff.config.Configuration;
import com.shootoff.config.ConfigurationException;
import com.shootoff.gui.controller.ShootOFFController;
import com.shootoff.gui.targets.TargetView;
import com.shootoff.session.Event;
import com.shootoff.session.EventType;
import com.shootoff.session.SessionRecorder;
import com.shootoff.session.TargetMovedEvent;
import com.shootoff.session.TargetRemovedEvent;
import com.shootoff.session.TargetResizedEvent;

import javafx.collections.FXCollections;
import javafx.scene.Group;

class TestCanvasManagerSessionRecording {
	private static final String CAMERA = "Default";

	private Configuration config;
	private SessionRecorder recorder;
	private CanvasManager canvas;

	@BeforeEach
	void setUp() throws ConfigurationException {
		config = new Configuration(new String[0]);
		recorder = new SessionRecorder();
		config.setSessionRecorder(recorder);
		canvas = new CanvasManager(new Group(), new ShootOFFController(), CAMERA,
				FXCollections.observableArrayList());
	}

	@AfterEach
	void tearDown() {
		config.setSessionRecorder(null);
	}

	private TargetView newTarget() {
		return new TargetView(new File("targets/shoot.target"), new Group(), new HashMap<String, String>(), canvas,
				false);
	}

	private List<EventType> eventTypes() {
		return recorder.getCameraEvents(CAMERA).stream().map(Event::getType).collect(Collectors.toList());
	}

	@Test
	void canvasesRecordByDefault() {
		assertTrue(canvas.recordsSessionEvents());
	}

	@Test
	void addingTargetRecordsAddPositionAndSize() {
		canvas.addTarget(newTarget());

		assertEquals(List.of(EventType.TARGET_ADDED, EventType.TARGET_MOVED, EventType.TARGET_RESIZED), eventTypes());
		final List<Event> events = recorder.getCameraEvents(CAMERA);
		assertEquals(0, ((TargetMovedEvent) events.get(1)).getTargetIndex());
		assertEquals(0, ((TargetResizedEvent) events.get(2)).getTargetIndex());
	}

	@Test
	void removingTargetRecordsOneRemoveWithValidIndex() {
		final TargetView target = newTarget();
		canvas.addTarget(target);

		canvas.removeTarget(target);

		final List<Event> events = recorder.getCameraEvents(CAMERA);
		assertEquals(1, eventTypes().stream().filter(t -> t == EventType.TARGET_REMOVED).count());
		assertEquals(0, ((TargetRemovedEvent) events.get(events.size() - 1)).getTargetIndex());
	}

	@Test
	void nonRecordingCanvasRecordsNothing() {
		canvas.setRecordsSessionEvents(false);
		final TargetView target = newTarget();

		canvas.addTarget(target);
		target.setPosition(10, 20);
		target.setDimensions(30, 40);
		canvas.removeTarget(target);

		assertEquals(List.of(), eventTypes());
	}

	@Test
	void unregisteredTargetRecordsNothing() {
		final TargetView target = newTarget(); // parent is canvas, but never added to it

		target.setPosition(10, 20);
		target.setDimensions(30, 40);

		assertEquals(List.of(), eventTypes());
	}
}
