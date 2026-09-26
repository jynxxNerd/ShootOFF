package com.shootoff.gui.pane;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import com.shootoff.config.Configuration;
import com.shootoff.config.ConfigurationException;
import com.shootoff.gui.ExerciseListener;
import com.shootoff.gui.JavaFXThreadingRule;
import com.shootoff.plugins.SteelChallenge;
import com.shootoff.plugins.TrainingExercise;
import com.shootoff.plugins.engine.LegacyExerciseEntry;
import com.shootoff.plugins.engine.PluginEngine;

import javafx.scene.Node;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;

public class TestExerciseSlide {
	@Rule public JavaFXThreadingRule javafxRule = new JavaFXThreadingRule();

	private final VBox body = new VBox();
	private ExerciseSlide slide;

	@Before
	public void setUp() throws ConfigurationException {
		System.setProperty("shootoff.home", System.getProperty("user.dir"));
		new Configuration(new String[0]);

		slide = new ExerciseSlide(new HBox(), body, new ExerciseListener() {
			@Override
			public void setProjectorExercise(TrainingExercise exercise) {}

			@Override
			public void setExercise(TrainingExercise exercise) {}

			@Override
			public PluginEngine getPluginEngine() {
				return null;
			}
		});
		slide.showBody();
	}

	// The names on the Training menu's buttons, both panes
	private List<String> menuNames() {
		final List<String> names = new ArrayList<>();
		for (final Node node : body.getChildren()) {
			if (!(node instanceof VBox panes)) continue;

			for (final Node pane : panes.getChildren()) {
				final ItemSelectionPane<?> items = (ItemSelectionPane<?>) ((TitledPane) pane).getContent();
				for (final Node button : ((Pane) items.getContent()).getChildren()) {
					names.add(((ButtonBase) button).getText());
				}
			}
		}
		return names;
	}

	@Test
	public void unregisteringAProjectorExerciseRemovesItsButton() {
		final LegacyExerciseEntry steel = new LegacyExerciseEntry(new SteelChallenge(), true);

		slide.registerProjectorExercise(steel);
		assertTrue(menuNames().contains("Steel Challenge"));

		slide.unregisterExercise(steel);
		assertFalse(menuNames().contains("Steel Challenge"));
	}
}
