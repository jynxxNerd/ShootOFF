package com.shootoff.plugins.engine;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

public class TestPluginEngine {
	private String pluginsPath;
	private PluginEngine pe;

	@Before
	public void setUp() throws IOException {
		pluginsPath = System.getProperty("user.dir") + File.separator + "javafx-app" + File.separator + "src"
				+ File.separator + "test" + File.separator + "exercises";
		System.setProperty("shootoff.plugins", pluginsPath);

		pe = new PluginEngine(new PluginListener() {
			@Override
			public void registerExercise(ExerciseEntry exercise) {}

			@Override
			public void registerProjectorExercise(ExerciseEntry exercise) {}

			@Override
			public void unregisterExercise(ExerciseEntry exercise) {}
		}, ExerciseLoaders.all(), List.of());
	}

	@Test
	public void testExistingPlugins() {
		assertEquals(2, pe.getPlugins().size());
	}
}
