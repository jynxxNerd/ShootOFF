package com.shootoff.gui.pane;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import com.shootoff.camera.CameraManager;
import com.shootoff.camera.CameraView;
import com.shootoff.config.Configuration;
import com.shootoff.config.ConfigurationException;
import com.shootoff.config.Settings;
import com.shootoff.gui.CanvasManager;
import com.shootoff.gui.JavaFXThreadingRule;
import com.shootoff.gui.ShotEntry;
import com.shootoff.gui.controller.ShootOFFController;
import com.shootoff.targets.CameraViews;
import com.shootoff.util.UserNotifier;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.layout.Pane;

// The Add Target menu is a user-initiated target load (Fix 2): a malformed file must notify the
// user through the UserNotifier instead of failing silently.
public class TestTargetSlide {
	@Rule public JavaFXThreadingRule javafxRule = new JavaFXThreadingRule();

	private static final class RecordingUserNotifier implements UserNotifier {
		final List<String> messages = new ArrayList<>();

		@Override
		public void showError(String title, String header, String message) {
			messages.add(title + " | " + header + " | " + message);
		}
	}

	private UserNotifier previousNotifier;
	private RecordingUserNotifier notifier;
	private File brokenTarget;
	private CanvasManager canvasManager;

	@Before
	public void setUp() throws ConfigurationException, IOException {
		System.setProperty("shootoff.home", System.getProperty("user.dir"));

		new Configuration(new String[0]);

		previousNotifier = Settings.getUserNotifier();
		notifier = new RecordingUserNotifier();
		Settings.setUserNotifier(notifier);

		brokenTarget = File.createTempFile("broken", ".target");
		Files.writeString(brokenTarget.toPath(),
				"<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<target>\n\t<ellipse centerX=\"1\"\n</target>\n",
				StandardCharsets.UTF_8);

		canvasManager = new CanvasManager(new Group(), new ShootOFFController(), "test",
				FXCollections.observableArrayList());
	}

	@After
	public void tearDown() {
		Settings.setUserNotifier(previousNotifier);
		if (brokenTarget != null) brokenTarget.delete();
	}

	private CameraViews cameraViewsFor(CanvasManager cm) {
		return new CameraViews() {
			@Override
			public void addNonCameraView(String name, Pane content, CanvasManager canvasManager, boolean select,
					boolean maximizeView) {}

			@Override
			public void removeCameraView(String name) {}

			@Override
			public boolean isArenaViewSelected() {
				return false;
			}

			@Override
			public CameraView getSelectedCameraView() {
				return cm;
			}

			@Override
			public CameraManager getSelectedCameraManager() {
				return null;
			}

			@Override
			public Node getSelectedCameraContainer() {
				return null;
			}

			@Override
			public void selectCameraView(CameraView cameraView) {}

			@Override
			public ObservableList<ShotEntry> getShotTimerModel() {
				return FXCollections.observableArrayList();
			}
		};
	}

	@Test
	public void addTargetWithAMalformedFileNotifiesTheUser() {
		final Pane parentControls = new Pane();
		final Pane parentBody = new Pane();

		final TargetSlide slide = new TargetSlide(parentControls, parentBody, cameraViewsFor(canvasManager));
		slide.showControls();

		Button addButton = null;
		for (final Node node : parentControls.getChildren()) {
			if (node instanceof Button && "Add Target".equals(((Button) node).getText())) {
				addButton = (Button) node;
				break;
			}
		}
		assertNotNull("Add Target button not found", addButton);
		addButton.fire();

		slide.onItemClicked(brokenTarget);

		assertEquals(1, notifier.messages.size());
		assertTrue(notifier.messages.get(0), notifier.messages.get(0).contains(brokenTarget.getPath()));
	}
}
