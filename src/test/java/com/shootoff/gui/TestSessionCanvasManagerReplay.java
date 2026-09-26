package com.shootoff.gui;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.shootoff.camera.Shot;
import com.shootoff.camera.shot.DisplayShot;
import com.shootoff.camera.shot.ShotColor;
import com.shootoff.config.Configuration;
import com.shootoff.config.ConfigurationException;
import com.shootoff.session.Event;
import com.shootoff.session.SessionRecorder;
import com.shootoff.session.ShotEvent;
import com.shootoff.session.TargetAddedEvent;
import com.shootoff.session.io.SessionIO;

import javafx.scene.Group;
import javafx.scene.Node;

class TestSessionCanvasManagerReplay {
	private Configuration config;
	private Group canvas;
	private SessionCanvasManager viewer;

	@BeforeEach
	void setUp() throws ConfigurationException {
		System.setProperty("shootoff.home", System.getProperty("user.dir"));
		config = new Configuration(new String[0]);
		canvas = new Group();
		viewer = new SessionCanvasManager(canvas, config);
	}

	@Test
	void replaysRecordedArenaSessionWithDuplicateTargetsAndBadIndexes() {
		final Optional<SessionRecorder> session = SessionIO
				.loadSession(new File("src/test/resources/sessions/arena_duplicate_targets.xml"));
		assertTrue(session.isPresent());
		final List<Event> events = session.get().getCameraEvents("arena");
		assertTrue(events.size() > 100);

		assertDoesNotThrow(() -> events.forEach(viewer::doEvent));

		final List<Event> reversed = new ArrayList<>(events);
		Collections.reverse(reversed);
		assertDoesNotThrow(() -> reversed.forEach(viewer::undoEvent));
	}

	@Test
	void shotOnUnknownTargetStillShowsMarker() {
		final DisplayShot shot = new DisplayShot(new Shot(ShotColor.RED, 5, 5, 0), 2);
		final ShotEvent event = new ShotEvent("arena", 0, shot, false, false, Optional.of(7), Optional.of(0),
				Optional.empty());

		assertDoesNotThrow(() -> viewer.doEvent(event));
		assertTrue(canvas.getChildren().contains(shot.getMarker()));

		assertDoesNotThrow(() -> viewer.undoEvent(event));
		assertTrue(!canvas.getChildren().contains(shot.getMarker()));
	}

	@Test
	void redoingAnEventAfterUndoDoesNotDuplicateNodes() {
		final DisplayShot shot = new DisplayShot(new Shot(ShotColor.RED, 5, 5, 0), 2);
		final ShotEvent event = new ShotEvent("arena", 0, shot, false, false, Optional.empty(), Optional.empty(),
				Optional.empty());

		viewer.doEvent(event);
		assertDoesNotThrow(() -> viewer.doEvent(event)); // re-applied without an undo
		final long markers = canvas.getChildren().stream().filter((Node n) -> n == shot.getMarker()).count();
		assertEquals(1, markers);
	}

	@Test
	void missingTargetFileIsSkipped() {
		final TargetAddedEvent added = new TargetAddedEvent("arena", 0, "no_such_dir/no_such.target");
		final int childrenBefore = canvas.getChildren().size();

		assertDoesNotThrow(() -> viewer.doEvent(added));
		assertDoesNotThrow(() -> viewer.undoEvent(added));
		assertEquals(childrenBefore, canvas.getChildren().size());
	}
}
