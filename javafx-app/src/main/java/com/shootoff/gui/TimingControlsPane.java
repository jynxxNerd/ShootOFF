/*
 * ShootOFF - Software for Laser Dry Fire Training
 * Copyright (C) 2016 phrack
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.shootoff.gui;

import java.util.Optional;

import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Region;

/**
 * The controls for an exercise's random start delay and, optionally, its par time. v1 exercises get
 * them through TrainingExerciseBase.getDelayedStartInterval/getParInterval, v2 exercises through
 * ExerciseHost.onDelayedStartChanged/onParTimeChanged. Setting a field's text notifies the listener,
 * exactly as typing does.
 */
public class TimingControlsPane extends GridPane {
	private final TextField minTextField = new TextField("4");
	private final TextField maxTextField = new TextField("8");
	private Optional<TextField> parTextField = Optional.empty();

	public TimingControlsPane(DelayedStartListener listener) {
		getColumnConstraints().add(new ColumnConstraints(100));
		setVgap(5);

		final Label instructionsLabel = new Label(
				"Set interval within which a beep will sound to signal the start of a round.\n");
		instructionsLabel.setPrefSize(Region.USE_COMPUTED_SIZE, Region.USE_COMPUTED_SIZE);

		this.add(instructionsLabel, 0, 0, 2, 3);
		addRow(3, new Label("Min (s)"));
		addRow(4, new Label("Max (s)"));

		this.add(minTextField, 1, 3);
		this.add(maxTextField, 1, 4);

		minTextField.textProperty().addListener((observable, oldValue, newValue) -> {
			if (!newValue.matches("\\d*")) {
				minTextField.setText(oldValue);
				minTextField.positionCaret(minTextField.getLength());
			} else {
				listener.updatedDelayedStartInterval(Integer.parseInt(minTextField.getText()),
						Integer.parseInt(maxTextField.getText()));
			}
		});

		maxTextField.textProperty().addListener((observable, oldValue, newValue) -> {
			if (!newValue.matches("\\d*")) {
				maxTextField.setText(oldValue);
				maxTextField.positionCaret(maxTextField.getLength());
			} else {
				listener.updatedDelayedStartInterval(Integer.parseInt(minTextField.getText()),
						Integer.parseInt(maxTextField.getText()));
			}
		});
	}

	/**
	 * Adds the par time row. A second call does nothing.
	 */
	public void addParTime(ParListener listener) {
		if (parTextField.isPresent()) return;

		final TextField field = new TextField("2.0");
		addRow(5, new Label("PAR Time (s)"));
		this.add(field, 1, 5);

		field.textProperty().addListener((observable, oldValue, newValue) -> {
			if (!newValue.matches("^\\d*\\.?\\d*$")) {
				field.setText(oldValue);
				field.positionCaret(field.getLength());
			} else {
				listener.updatedParInterval(Double.parseDouble(field.getText()));
			}
		});

		parTextField = Optional.of(field);
	}

	public boolean hasParTime() {
		return parTextField.isPresent();
	}

	public void setDelayRange(int minSeconds, int maxSeconds) {
		minTextField.setText(Integer.toString(minSeconds));
		maxTextField.setText(Integer.toString(maxSeconds));
	}

	public void setParTime(double seconds) {
		parTextField.ifPresent(field -> field.setText(Double.toString(seconds)));
	}

	public String getMinText() {
		return minTextField.getText();
	}

	public String getMaxText() {
		return maxTextField.getText();
	}

	public Optional<String> getParTimeText() {
		return parTextField.map(TextField::getText);
	}
}
