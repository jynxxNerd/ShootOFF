package com.shootoff.exercise;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.shootoff.JavaFxReferenceScanner;

class TestNoJavaFxInPluginApi {
	@Test
	void pluginApiClassesDoNotReferenceJavaFx() throws Exception {
		assertEquals(List.of(), JavaFxReferenceScanner.findJavaFxReferencesNextTo(Exercise.class));
	}

	@Test
	void testFixturesDoNotReferenceJavaFx() throws Exception {
		assertEquals(List.of(), JavaFxReferenceScanner.findJavaFxReferencesNextTo(FakeExerciseHost.class));
	}
}
