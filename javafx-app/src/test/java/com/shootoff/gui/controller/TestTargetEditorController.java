package com.shootoff.gui.controller;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import com.shootoff.config.Settings;
import com.shootoff.gui.JavaFXThreadingRule;
import com.shootoff.gui.targets.TargetListener;
import com.shootoff.util.UserNotifier;

import javafx.fxml.FXMLLoader;
import javafx.scene.layout.Pane;

// A malformed target must not open an editor bound to it (Fix 2): the failure is reported through
// the UserNotifier instead of silently leaving a blank editor whose "Save" would overwrite the
// user's file with an empty target.
public class TestTargetEditorController {
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

	@Before
	public void setUp() throws IOException {
		System.setProperty("shootoff.home", System.getProperty("user.dir"));

		previousNotifier = Settings.getUserNotifier();
		notifier = new RecordingUserNotifier();
		Settings.setUserNotifier(notifier);

		brokenTarget = File.createTempFile("broken", ".target");
		Files.writeString(brokenTarget.toPath(),
				"<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<target>\n\t<ellipse centerX=\"1\"\n</target>\n",
				StandardCharsets.UTF_8);
	}

	@After
	public void tearDown() {
		Settings.setUserNotifier(previousNotifier);
		if (brokenTarget != null) brokenTarget.delete();
	}

	private TargetEditorController loadController() throws IOException {
		final FXMLLoader loader = new FXMLLoader(
				getClass().getClassLoader().getResource("com/shootoff/gui/TargetEditor.fxml"));
		loader.load();
		return loader.getController();
	}

	@Test
	public void malformedTargetDoesNotOpenOrBindAndNotifiesTheUser() throws IOException {
		final TargetEditorController controller = loadController();
		final TargetListener listener = (file) -> {};

		final boolean loaded = controller.init(null, listener, brokenTarget);

		assertFalse("init should report failure for a malformed target", loaded);

		// The editor isn't bound to the broken file: nothing from it was added to the canvas
		final Pane canvasPane = (Pane) controller.getPane().getChildren().get(1);
		assertTrue("the canvas should have no regions from the malformed target", canvasPane.getChildren().isEmpty());

		assertEquals(1, notifier.messages.size());
		assertTrue(notifier.messages.get(0), notifier.messages.get(0).contains(brokenTarget.getPath()));
	}
}
