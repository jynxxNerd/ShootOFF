package com.shootoff.testutil;

import javafx.embed.swing.JFXPanel;
import org.junit.platform.launcher.TestExecutionListener;

/**
 * Forces the JavaFX toolkit (GTK 3) to initialize before any test runs.
 *
 * Several tests load OpenCV's native library, which pulls in GTK 2 via its
 * highgui module. OpenJFX no longer supports GTK 2, and glass refuses to
 * start if GTK 2 is already loaded in the process, so JavaFX must claim GTK
 * first. All tests in a Gradle test task run in the same JVM, so doing this
 * once here - before the test plan starts - covers every test class
 * regardless of which one happens to touch OpenCV or JavaFX first.
 *
 * Discovered via the ServiceLoader entry in
 * src/test/resources/META-INF/services/org.junit.platform.launcher.TestExecutionListener.
 */
public class JavaFXToolkitInitializer implements TestExecutionListener {
	@Override
	public void testPlanExecutionStarted(org.junit.platform.launcher.TestPlan testPlan) {
		new JFXPanel();
	}
}
