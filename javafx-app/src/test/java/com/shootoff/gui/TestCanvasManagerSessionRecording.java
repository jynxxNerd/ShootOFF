package com.shootoff.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.shootoff.config.Configuration;
import com.shootoff.config.ConfigurationException;
import com.shootoff.gui.controller.ShootOFFController;
import com.shootoff.gui.targets.MirroredTarget;
import com.shootoff.gui.targets.TargetView;
import com.shootoff.targets.io.TargetIO.TargetComponents;
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
		return new TargetView(TargetComponents.empty(new File("targets/shoot.target")), canvas, false);
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

	@Test
	void targetWithoutFileIsNeverRecorded() {
		// e.g. the manual calibration rectangle
		final TargetView fileless = new TargetView(TargetComponents.empty(null), canvas, false);

		canvas.addTarget(fileless);
		fileless.setPosition(10, 20);
		canvas.removeTarget(fileless);

		assertEquals(List.of(), eventTypes());
	}

	@Test
	void mirroredResizeIsRecordedOnTheRecordingCanvas() {
		// Dragging a target on the (silent) arena tab resizes the projector copy through
		// mirrorSetDimensions; that resize must be recorded
		final MirroredTarget projectorCopy = new MirroredTarget(TargetComponents.empty(new File("targets/shoot.target")),
				config, canvas, false);
		canvas.addTarget(projectorCopy);
		recorder.getCameraEvents(CAMERA).clear();

		projectorCopy.mirrorSetDimensions(50, 60);

		assertEquals(List.of(EventType.TARGET_RESIZED), eventTypes());
		final TargetResizedEvent resized = (TargetResizedEvent) recorder.getCameraEvents(CAMERA).get(0);
		assertEquals(50, resized.getNewWidth(), 0.001);
		assertEquals(60, resized.getNewHeight(), 0.001);
	}
}
